package com.gao.agent.service.browser;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.gao.agent.model.*;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gao.agent.service.agent.ActionExecutor;
import com.gao.agent.service.agent.AgentLoopService;
import com.gao.agent.service.agent.PageStateService;

/**
 * 基于 Selenium 的浏览器自动化服务实现。
 * 提供两种执行模式：
 * <ul>
 *   <li>预生成计划模式（executeSteps）：按预定义的动作列表逐步执行</li>
 *   <li>Agent Loop 模式（runAgentLoop / runAgentLoopWithSse）：由 LLM 逐步实时决策执行</li>
 * </ul>
 *
 * 核心职责：
 * <ul>
 *   <li>浏览器生命周期管理：创建 Edge/Chrome 驱动，支持可视化/无头模式</li>
 *   <li>页面元素采集：通过 JS 脚本扫描可交互元素，生成面向 LLM 的文本描述</li>
 *   <li>Agent Loop 暂停/恢复：通过 CountDownLatch 阻塞等待用户输入，支持多次暂停</li>
 *   <li>Session 管理：通过 ConcurrentHashMap 维护活跃会话，支持外部查询和关闭</li>
 * </ul>
 */
@Service
public class SeleniumBrowserAutomationService implements BrowserAutomationService {

    private static final Logger log = LoggerFactory.getLogger(SeleniumBrowserAutomationService.class);

    private final PageStateService pageStateService;
    private final ActionExecutor executor;
    private final AgentLoopService agentLoopService;
    /** 活跃会话表：taskId → AgentSession，用于暂停/恢复场景 */
    private final Map<String, AgentSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * JS 元素采集脚本（预生成计划模式专用）。
     * 与 PageStateService 中的脚本功能类似，但额外收集 cls（CSS 类名）属性，
     * 用于 findElementByIndex 中通过 CSS 选择器定位元素。
     * 注意：Agent Loop 模式使用 PageStateService 的采集脚本，不走此脚本。
     */
    private static final String JS_COLLECT =
        "var IC=new Set(['pointer','move','text','grab','grabbing','cell','copy','alias'," +
        "'all-scroll','col-resize','context-menu','crosshair','e-resize','ew-resize','help'," +
        "'n-resize','ne-resize','nesw-resize','ns-resize','nw-resize','nwse-resize','row-resize'," +
        "'s-resize','se-resize','sw-resize','vertical-text','w-resize','zoom-in','zoom-out']);" +
        "var IR=new Set(['button','link','menuitem','menuitemradio','menuitemcheckbox','radio'," +
        "'checkbox','tab','switch','slider','spinbutton','combobox','searchbox','textbox'," +
        "'listbox','option','listitem','treeitem','row','scrollbar','menu','menubar']);" +
        "var IA=['aria-expanded','aria-checked','aria-selected','aria-pressed','aria-haspopup'," +
        "'aria-controls','aria-owns','aria-activedescendant'];" +
        "var SKIP=new Set(['script','style','link','meta','noscript','template','svg','head','br','hr']);" +
        "var R=[];" +
        "function vis(e){if(!e)return false;try{var r=e.getBoundingClientRect();" +
        "if(r.width<2||r.height<2)return false;" +
        "var s=getComputedStyle(e);" +
        "return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity)>0;}catch(x){return false;}}" +
        "function inter(e){" +
        "if(!e||e.nodeType!==1)return false;" +
        "var t=e.tagName.toLowerCase();if(SKIP.has(t))return false;" +
        "if(!vis(e))return false;" +
        "var s=getComputedStyle(e);" +
        "if(IC.has(s.cursor))return true;" +
        "if(['a','button','input','select','textarea','details','summary','label'].indexOf(t)>=0){" +
        "if(!e.disabled&&!e.readOnly)return true;}" +
        "if(e.isContentEditable||e.getAttribute('contenteditable')==='true')return true;" +
        "var rl=e.getAttribute('role');if(rl&&IR.has(rl))return true;" +
        "for(var i=0;i<IA.length;i++){if(e.hasAttribute(IA[i]))return true;}" +
        "if(e.hasAttribute('onclick')||e.hasAttribute('tabindex'))return true;" +
        "if(e.hasAttribute('data-action')||e.hasAttribute('data-toggle'))return true;" +
        "var cn=e.className||'';" +
        "if(typeof cn!=='string')cn=cn.baseVal||'';" +
        "if(cn.indexOf('el-menu-item')>=0||cn.indexOf('el-submenu__title')>=0||" +
        "cn.indexOf('el-select')>=0||cn.indexOf('el-button')>=0||" +
        "cn.indexOf('el-checkbox')>=0||cn.indexOf('el-radio')>=0||" +
        "cn.indexOf('el-switch')>=0||cn.indexOf('el-tab')>=0||" +
        "cn.indexOf('ant-menu')>=0||cn.indexOf('ant-select')>=0||" +
        "cn.indexOf('ant-btn')>=0||cn.indexOf('ant-checkbox')>=0||" +
        "cn.indexOf('ant-radio')>=0||cn.indexOf('ant-switch')>=0||" +
        "cn.indexOf('dropdown-toggle')>=0||cn.indexOf('nav-item')>=0||" +
        "cn.indexOf('sidebar-item')>=0||cn.indexOf('menu-item')>=0)return true;" +
        "var p=e.parentElement;" +
        "if(p){var ps=getComputedStyle(p);" +
        "if(IC.has(ps.cursor)&&s.cursor==='auto'){" +
        "var r=e.getBoundingClientRect();" +
        "if(r.width>0&&r.height>0&&r.width<600&&r.height<120)return true;}}" +
        "return false;}" +
        "function walk(el){" +
        "if(!el)return;" +
        "var ch=el.children;" +
        "for(var i=0;i<ch.length;i++){var c=ch[i];" +
        "var t=c.tagName.toLowerCase();if(SKIP.has(t))continue;" +
        "if(inter(c)){var r=c.getBoundingClientRect();" +
        "if(r.width>0&&r.height>0){" +
        "var tx=(c.innerText||c.value||'').trim();" +
        "if(tx.length>120)tx=tx.substring(0,120)+'...';" +
        "R.push({tag:t,text:tx,type:c.getAttribute('type')||'',name:c.getAttribute('name')||''," +
        "id:c.getAttribute('id')||'',ph:c.getAttribute('placeholder')||''," +
        "al:c.getAttribute('aria-label')||'',role:c.getAttribute('role')||''," +
        "href:c.getAttribute('href')||'',cls:(c.className&&typeof c.className==='string'?c.className:'').split(' ').slice(0,3).join(' ')});}}" +
        "walk(c);}}" +
        "walk(document.body);return R;";

    public SeleniumBrowserAutomationService(PageStateService pageStateService,
                                            ActionExecutor executor,
                                            AgentLoopService agentLoopService) {
        this.pageStateService = pageStateService;
        this.executor = executor;
        this.agentLoopService = agentLoopService;
    }

    /** 执行预定义步骤（简化版，默认 Edge + 无头模式） */
    public TestExecutionResult executeSteps(String targetUrl, List<TestAction> steps) {
        return executeSteps(targetUrl, steps, "EDGE", false);
    }

    /**
     * 执行预定义的测试步骤列表。
     * 流程：创建浏览器 → 导航到目标页面 → 采集元素 → 逐步执行动作 → 关闭浏览器。
     */
    @Override
    public TestExecutionResult executeSteps(String targetUrl, List<TestAction> steps, String browserName, boolean visual) {
        TestExecutionResult result = new TestExecutionResult();

        WebDriver driver = null;
        try {
            driver = createDriver(browserName, visual);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            
            String pageElements = collectPageElements(driver, targetUrl);
            log.info("Collected page elements before execution:\n{}", pageElements);

            for (TestAction action : steps) {
                TestStepResult stepResult = executeAction(driver, action);
                result.addStep(stepResult);
                if (!stepResult.isSuccess()) {
                    result.setSuccess(false);
                    result.setMessage("Test execution failed at step: " + stepResult.getDescription());
                    result.setDetails(stepResult.getDetails());
                    return result;
                }
            }
            result.setSuccess(true);
            result.setMessage("Test execution completed");
        } catch (Exception ex) {
            log.error("Browser automation exception", ex);
            result.setSuccess(false);
            result.setMessage("Test execution failed");
            result.setDetails(getStackTrace(ex));
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
        return result;
    }

    /**
     * 采集目标页面上的可交互元素（预生成计划模式专用）。
     * 导航到页面 → 执行 JS_COLLECT 脚本 → 组装元素描述文本 → 检测验证码文本。
     */
    private String collectPageElements(WebDriver driver, String targetUrl) {
        try {
            driver.get(targetUrl);
            sleep(2000);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jsResults = (List<Map<String, Object>>)
                    ((JavascriptExecutor) driver).executeScript(JS_COLLECT);

            StringBuilder sb = new StringBuilder();
            sb.append("页面 ").append(targetUrl).append(" 上的可交互元素列表（共 ").append(jsResults.size()).append(" 个）：\n");
            for (int i = 0; i < jsResults.size(); i++) {
                Map<String, Object> item = jsResults.get(i);
                String tag = str(item.get("tag"));
                String type = str(item.get("type"));
                String name = str(item.get("name"));
                String id = str(item.get("id"));
                String placeholder = str(item.get("ph"));
                String text = str(item.get("text"));
                String ariaLabel = str(item.get("al"));
                String role = str(item.get("role"));
                String href = str(item.get("href"));
                String cls = str(item.get("cls"));

                sb.append("[").append(i).append("] <").append(tag).append(">");
                if (!type.isEmpty()) sb.append(" type=").append(type);
                if (!name.isEmpty()) sb.append(" name=").append(name);
                if (!id.isEmpty()) sb.append(" id=").append(id);
                if (!placeholder.isEmpty()) sb.append(" placeholder=\"").append(placeholder).append("\"");
                if (!ariaLabel.isEmpty()) sb.append(" aria-label=\"").append(ariaLabel).append("\"");
                if (!role.isEmpty()) sb.append(" role=").append(role);
                if (!href.isEmpty()) sb.append(" href=\"").append(href).append("\"");
                if (!cls.isEmpty()) sb.append(" class=\"").append(cls).append("\"");
                if (!text.isEmpty()) {
                    sb.append(" text=\"").append(text, 0, Math.min(60, text.length())).append("\"");
                }
                sb.append("\n");
            }

            collectCaptchaText(driver, sb);

            log.info("Collected page elements:\n{}", sb.toString());
            return sb.toString();
        } catch (Exception ex) {
            log.error("Failed to collect page elements", ex);
            return "无法获取页面元素信息：" + ex.getMessage();
        }
    }

    /**
     * 检测页面上的验证码/短文本内容。
     * 通过 TreeWalker 遍历所有文本节点，筛选 2-8 位纯字母数字的短文本，
     * 这些很可能是验证码图片旁显示的文字。
     */
    private void collectCaptchaText(WebDriver driver, StringBuilder sb) {
        try {
            Object captchaResult = ((JavascriptExecutor) driver).executeScript(
                "var results=[];var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);" +
                "var n;while(n=w.nextNode()){var t=n.textContent.trim();" +
                "if(t.length>=2&&t.length<=8&&/^[A-Za-z0-9]+$/.test(t)){" +
                "var e=n.parentElement;if(e&&e.offsetParent!==null){" +
                "results.push('<'+e.tagName.toLowerCase()+'> text=\"'+t+'\"');}}}return results;"
            );
            if (captchaResult instanceof List && !((List<?>) captchaResult).isEmpty()) {
                sb.append("\n页面上的验证码/短文本内容：\n");
                @SuppressWarnings("unchecked")
                List<String> captchaTexts = (List<String>) captchaResult;
                int count = 0;
                for (String captchaText : captchaTexts) {
                    if (count >= 20) break;
                    sb.append("- ").append(captchaText).append("\n");
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to detect captcha text via JS: {}", e.getMessage());
        }
    }

    /** 安全地将 Object 转为 String，null 返回空字符串 */
    private String str(Object o) {
        return o != null ? o.toString() : "";
    }

    /**
     * 创建浏览器驱动。
     * 优先创建请求的浏览器类型，失败时自动降级到另一种（Edge → Chrome 或 Chrome → Edge）。
     *
     * @param browserName 浏览器类型（EDGE / CHROME）
     * @param visual      是否可视化模式（false = 无头模式）
     * @return WebDriver 实例
     * @throws IllegalStateException 两种浏览器都无法启动时抛出
     */
    private WebDriver createDriver(String browserName, boolean visual) {
        String requested = (browserName == null || browserName.isBlank()) ? "EDGE" : browserName.toUpperCase();
        String fallback = "EDGE".equals(requested) ? "CHROME" : "EDGE";
        Exception lastError = null;

        for (String candidate : new String[]{requested, fallback}) {
            try {
                log.info("Trying to start {} driver...", candidate);
                WebDriver driver = "EDGE".equals(candidate) ? createEdgeDriver(visual) : createChromeDriver(visual);
                log.info("{} driver started successfully", candidate);
                return driver;
            } catch (Exception ex) {
                log.warn("{} driver failed: {}", candidate, ex.getMessage());
                lastError = ex;
            }
        }

        throw new IllegalStateException(
                "Failed to start any browser (" + requested + ", " + fallback + "). " +
                        "Last error: " + (lastError != null ? lastError.getMessage() : "unknown") +
                        ". Please install Microsoft Edge or Google Chrome.", lastError);
    }

    /** 创建 Edge 驱动（支持可视化/无头模式） */
    private WebDriver createEdgeDriver(boolean visual) {
        EdgeOptions options = new EdgeOptions();
        if (!visual) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage", "--remote-allow-origins=*");

        String binary = locateBrowserBinary("EDGE");
        if (binary != null) {
            options.setBinary(binary);
            log.info("Using Edge binary: {}", binary);
        }
        return new EdgeDriver(options);
    }

    /** 创建 Chrome 驱动（支持可视化/无头模式） */
    private WebDriver createChromeDriver(boolean visual) {
        ChromeOptions options = new ChromeOptions();
        if (!visual) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage", "--remote-allow-origins=*");

        String binary = locateBrowserBinary("CHROME");
        if (binary != null) {
            options.setBinary(binary);
            log.info("Using Chrome binary: {}", binary);
        }
        return new ChromeDriver(options);
    }

    /**
     * 查找浏览器可执行文件路径。
     * 查找优先级：系统属性 > 环境变量 > 默认安装路径。
     */
    private String locateBrowserBinary(String browserName) {
        if ("CHROME".equalsIgnoreCase(browserName)) {
            String sysProp = System.getProperty("webdriver.chrome.bin");
            if (sysProp != null && !sysProp.isBlank()) return sysProp;
            String envVar = System.getenv("CHROME_BINARY_PATH");
            if (envVar != null && !envVar.isBlank()) return envVar;
            return findExisting(
                    "C:/Program Files/Google/Chrome/Application/chrome.exe",
                    "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"
            );
        } else if ("EDGE".equalsIgnoreCase(browserName)) {
            String sysProp = System.getProperty("webdriver.edge.bin");
            if (sysProp != null && !sysProp.isBlank()) return sysProp;
            String envVar = System.getenv("EDGE_BINARY_PATH");
            if (envVar != null && !envVar.isBlank()) return envVar;
            return findExisting(
                    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                    "C:/Program Files/Microsoft/Edge/Application/msedge.exe"
            );
        }
        return null;
    }

    /** 检查文件路径是否存在，返回第一个存在的路径 */
    private String findExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && Files.exists(Paths.get(c))) {
                return c;
            }
        }
        return null;
    }

    /**
     * 执行单个测试动作（预生成计划模式）。
     * 支持 NAVIGATE / CLICK / TYPE / ASSERT_TEXT / WAIT / EXECUTE_JS 六种动作类型。
     */
    private TestStepResult executeAction(WebDriver driver, TestAction action) {
        String description;
        try {
            TestActionType type = action.getType();
            switch (type) {
                case NAVIGATE:
                    String url = String.valueOf(action.getParameters().get("url"));
                    driver.get(url);
                    sleep(2000);
                    description = "Navigate to " + url;
                    break;
                case CLICK:
                    WebElement clickElement = findElement(driver, action);
                    clickElement.click();
                    description = "Click element (index=" + action.getParameters().get("index") + ")";
                    break;
                case TYPE:
                    WebElement typeElement = findElement(driver, action);
                    Object text = action.getParameters().get("text");
                    typeElement.clear();
                    typeElement.sendKeys(text == null ? "" : String.valueOf(text));
                    description = "Type into element (index=" + action.getParameters().get("index") + "): " + text;
                    break;
                case ASSERT_TEXT:
                    WebElement assertElement = findElement(driver, action);
                    String actualText = assertElement.getText();
                    Object expectedValue = action.getParameters().getOrDefault("text", action.getParameters().get("expected"));
                    String expectedText = expectedValue == null ? "" : String.valueOf(expectedValue);
                    if (!actualText.contains(expectedText)) {
                        throw new IllegalStateException("Assertion failed for element (index=" + action.getParameters().get("index") + "): expected [" + expectedText + "] but found [" + actualText + "]");
                    }
                    description = "Assert text contains " + expectedText + " for element (index=" + action.getParameters().get("index") + ")";
                    break;
                case WAIT:
                    long sleepMillis = resolveWaitMillis(action);
                    sleep(sleepMillis);
                    description = "Wait for " + sleepMillis + " ms";
                    break;
                case EXECUTE_JS:
                    String script = String.valueOf(action.getParameters().get("script"));
                    Object scriptResult = ((JavascriptExecutor) driver).executeScript(script);
                    description = "Execute JS: " + script + " => " + scriptResult;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported action type: " + type);
            }
            String screenshot = captureScreenshot(driver);
            return new TestStepResult(description, true, screenshot, null);
        } catch (Exception ex) {
            String screenshot = captureScreenshot(driver);
            return new TestStepResult("Failed step " + action.getType(), false, screenshot, ex.getMessage());
        }
    }

    /** 解析 WAIT 动作的等待时间，支持 ms 和 seconds 两种参数 */
    private long resolveWaitMillis(TestAction action) {
        Object waitMsValue = action.getParameters().get("ms");
        Object secondsValue = action.getParameters().get("seconds");
        if (waitMsValue instanceof Number) return ((Number) waitMsValue).longValue();
        if (waitMsValue != null) return Long.parseLong(String.valueOf(waitMsValue));
        if (secondsValue instanceof Number) return ((Number) secondsValue).longValue() * 1000L;
        if (secondsValue != null) return Long.parseLong(String.valueOf(secondsValue)) * 1000L;
        return 1000L;
    }

    /** 截取当前页面截图，返回 base64 编码的 data URI */
    private String captureScreenshot(WebDriver driver) {
        try {
            if (driver instanceof TakesScreenshot takesScreenshot) {
                return "data:image/png;base64," + takesScreenshot.getScreenshotAs(OutputType.BASE64);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 根据 TestAction 定位页面元素。
     * 优先使用 index 编号定位，fallback 到 selector（xpath/text=/cssSelector）。
     */
    private WebElement findElement(WebDriver driver, TestAction action) {
        Object indexObj = action.getParameters().get("index");
        if (indexObj instanceof Number) {
            int index = ((Number) indexObj).intValue();
            return findElementByIndex(driver, index);
        }
        
        String selector = String.valueOf(action.getParameters().get("selector"));
        if (selector == null || selector.isEmpty() || "null".equals(selector)) {
            throw new IllegalArgumentException("Index must be provided for element location");
        }
        selector = selector.trim();

        if (selector.startsWith("//") || selector.startsWith("xpath=")) {
            String xpath = selector.startsWith("xpath=") ? selector.substring("xpath=".length()) : selector;
            return driver.findElement(By.xpath(xpath));
        }

        if (selector.toLowerCase().startsWith("text=")) {
            String text = selector.substring(5).trim();
            return driver.findElement(By.xpath("//*[contains(normalize-space(string(.)), '" + escapeXPathText(text) + "')]"));
        }

        return driver.findElement(By.cssSelector(selector));
    }

    /**
     * 通过元素编号定位元素（预生成计划模式专用）。
     * 重新执行 JS_COLLECT 获取最新元素列表，然后根据目标元素的属性组合构建 CSS 选择器查找。
     * 查找策略：CSS 属性组合 → 文本内容 XPath → 属性精确匹配 → 位置匹配。
     */
    private WebElement findElementByIndex(WebDriver driver, int index) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jsResults = (List<Map<String, Object>>)
                ((JavascriptExecutor) driver).executeScript(JS_COLLECT);

        if (index < 0 || index >= jsResults.size()) {
            throw new NoSuchElementException("Element index " + index + " out of range [0, " + jsResults.size() + ")");
        }

        Map<String, Object> target = jsResults.get(index);
        String tag = (String) target.get("tag");
        String text = (String) target.get("text");
        String id = (String) target.get("id");
        String name = (String) target.get("name");
        String type = (String) target.get("type");
        String placeholder = (String) target.get("ph");

        // 策略1：通过 CSS 属性组合选择器查找
        StringBuilder cssBuilder = new StringBuilder(tag);
        if (!id.isEmpty()) cssBuilder.append("#").append(id);
        if (!name.isEmpty()) cssBuilder.append("[name='").append(name).append("']");
        if (!type.isEmpty()) cssBuilder.append("[type='").append(type).append("']");
        if (!placeholder.isEmpty()) cssBuilder.append("[placeholder='").append(placeholder).append("']");

        List<WebElement> candidates = driver.findElements(By.cssSelector(cssBuilder.toString()));
        for (WebElement c : candidates) {
            try {
                if (c.isDisplayed()) {
                    log.info("Found element at index {} by CSS: {}", index, cssBuilder);
                    return c;
                }
            } catch (Exception ignored) {}
        }

        // 策略2：通过文本内容 XPath 查找
        if (!text.isEmpty()) {
            String xpath = "//*[contains(normalize-space(string(.)), '" + escapeXPathText(text) + "')]";
            try {
                WebElement el = driver.findElement(By.xpath(xpath));
                if (el.isDisplayed()) {
                    log.info("Found element at index {} by text: {}", index, text);
                    return el;
                }
            } catch (Exception ignored) {}
        }

        // 策略3：通过属性精确匹配查找
        List<WebElement> allOfTag = driver.findElements(By.cssSelector(tag));
        for (WebElement el : allOfTag) {
            try {
                if (!el.isDisplayed()) continue;
                String elId = el.getAttribute("id");
                String elName = el.getAttribute("name");
                String elText = el.getText() != null ? el.getText().trim() : "";
                if ((!id.isEmpty() && id.equals(elId)) ||
                    (!name.isEmpty() && name.equals(elName)) ||
                    (!text.isEmpty() && text.equals(elText))) {
                    log.info("Found element at index {} by attribute match", index);
                    return el;
                }
            } catch (Exception ignored) {}
        }

        // 策略4：位置匹配（兜底）
        int posIdx = 0;
        for (WebElement el : allOfTag) {
            try {
                if (el.isDisplayed()) {
                    if (posIdx == index % Math.max(1, allOfTag.size())) {
                        log.info("Falling back to positional match at index {}", index);
                        return el;
                    }
                    posIdx++;
                }
            } catch (Exception ignored) {}
        }

        throw new NoSuchElementException("Cannot locate element at index " + index +
            ". Target: <" + tag + "> text=\"" + text + "\" id=\"" + id + "\"");
    }

    /** 转义 XPath 文本中的单引号（使用 concat 函数） */
    private String escapeXPathText(String text) {
        if (!text.contains("'")) return text;
        if (!text.contains("\"")) return text;
        String[] parts = text.split("'");
        StringBuilder builder = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append(", '\'', ");
            builder.append('"').append(parts[i]).append('"');
        }
        builder.append(")");
        return builder.toString();
    }

    /** Agent Loop 模式入口（无 SSE），委托给 runAgentLoopWithSse */
    @Override
    public AgentLoopResult runAgentLoop(String targetUrl, String taskDescription, String browserName, boolean visual) {
        return runAgentLoopWithSse(targetUrl, taskDescription, browserName, visual, null, null, null);
    }

    /**
     * Agent Loop 模式核心执行方法（带 SSE 推送和暂停/恢复支持）。
     *
     * 执行流程：
     * <ol>
     *   <li>创建浏览器驱动并导航到目标页面</li>
     *   <li>调用 AgentLoopService.run() 启动 Agent Loop</li>
     *   <li>如果 LLM 返回 needs_input → 保存 Session，阻塞等待用户输入</li>
     *   <li>收到用户输入后调用 AgentLoopService.resumeFromSession() 恢复执行</li>
     *   <li>支持多次暂停（while 循环），直到任务完成或超时</li>
     *   <li>任务完成/失败后关闭浏览器（needs_input 暂停时保持浏览器打开）</li>
     * </ol>
     *
     * @param targetUrl      目标页面 URL
     * @param taskDescription 自然语言任务描述
     * @param browserName    浏览器类型
     * @param visual         是否可视化模式
     * @param taskId         任务 ID（用于 Session 管理）
     * @param callback       暂停时的回调通知
     * @param emitter        SSE 发射器（用于实时推送进度）
     * @return Agent Loop 执行结果
     */
    public AgentLoopResult runAgentLoopWithSse(String targetUrl, String taskDescription,
                                                String browserName, boolean visual, String taskId,
                                                AgentLoopCallback callback,
                                                SseEmitter emitter) {
        WebDriver driver = null;
        boolean keepDriverOpen = false;
        
        // 尝试从已有 Session 中恢复 SSE emitter
        if (emitter == null && taskId != null) {
            AgentSession session = activeSessions.get(taskId);
            if (session != null && session.getEmitter() != null) {
                emitter = session.getEmitter();
            }
        }
        
        try {
            driver = createDriver(browserName, visual);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            driver.get(targetUrl);
            sleep(2000);

            // 启动 Agent Loop
            AgentLoopResult loopResult = agentLoopService.run(driver, targetUrl, taskDescription, taskId, emitter);

            // 处理 needs_input 暂停场景
            if (loopResult.getNeedsInputPrompt() != null && taskId != null) {
                keepDriverOpen = true;
                log.info("Agent needs input for task {}, keeping browser open", taskId);
                AgentSession session = agentLoopService.createSession(taskId, driver, loopResult);
                if (emitter != null) {
                    session.setEmitter(emitter);
                }
                activeSessions.put(taskId, session);

                if (callback != null) {
                    callback.onNeedsInput(loopResult);
                }

                // 阻塞等待用户输入（最长 5 分钟）
                try {
                    boolean gotInput = session.waitForInput(300000);
                    if (!gotInput) {
                        log.warn("Timeout waiting for user input for task {}", taskId);
                        activeSessions.remove(taskId);
                        keepDriverOpen = false;
                        loopResult.setMessage("等待用户输入超时");
                        return loopResult;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    activeSessions.remove(taskId);
                    keepDriverOpen = false;
                    loopResult.setMessage("等待用户输入被中断");
                    return loopResult;
                }

                log.info("User provided input for task {}, resuming", taskId);
                
                // 恢复执行，支持多次暂停循环
                SseEmitter resumeEmitter = session.getEmitter();
                AgentLoopResult resumeResult = agentLoopService.resumeFromSession(session, resumeEmitter);

                while (resumeResult.getNeedsInputPrompt() != null) {
                    log.info("Agent needs more input for task {}", taskId);
                    session.updateForNextInput(resumeResult);
                    
                    if (callback != null) {
                        callback.onNeedsInput(resumeResult);
                    }
                    
                    try {
                        boolean moreInput = session.waitForInput(300000);
                        if (!moreInput) {
                            activeSessions.remove(taskId);
                            keepDriverOpen = false;
                            resumeResult.setMessage("等待用户输入超时");
                            return resumeResult;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        activeSessions.remove(taskId);
                        keepDriverOpen = false;
                        return resumeResult;
                    }
                    
                    resumeEmitter = session.getEmitter();
                    resumeResult = agentLoopService.resumeFromSession(session, resumeEmitter);
                }

                activeSessions.remove(taskId);
                keepDriverOpen = false;
                return resumeResult;
            }

            return loopResult;
        } catch (Exception ex) {
            log.error("Agent loop failed", ex);
            AgentLoopResult err = new AgentLoopResult();
            err.setSuccess(false);
            err.setMessage("Agent loop crashed: " + ex.getMessage());
            err.setDetails(getStackTrace(ex));
            return err;
        } finally {
            if (driver != null && !keepDriverOpen) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
    }

    /** 线程休眠，捕获 InterruptedException 并恢复中断标志 */
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 获取异常堆栈字符串 */
    private String getStackTrace(Exception ex) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }

    /** 关闭指定任务的 Session，释放浏览器驱动资源 */
    public void closeSession(String taskId) {
        AgentSession session = activeSessions.remove(taskId);
        if (session != null) {
            session.closeDriver();
        }
    }

    /** 获取指定任务的 Session（用于外部查询暂停状态） */
    public AgentSession getSession(String taskId) {
        return activeSessions.get(taskId);
    }
}
