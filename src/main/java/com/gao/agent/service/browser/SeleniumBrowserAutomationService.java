package com.gao.agent.service.browser;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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

import com.gao.agent.service.agent.ActionExecutor;
import com.gao.agent.service.agent.AgentLoopService;
import com.gao.agent.service.agent.PageStateService;

@Service
public class SeleniumBrowserAutomationService implements BrowserAutomationService {

    private static final Logger log = LoggerFactory.getLogger(SeleniumBrowserAutomationService.class);

    private final PageStateService pageStateService;
    private final ActionExecutor executor;
    private final AgentLoopService agentLoopService;
    private final Map<String, AgentSession> activeSessions = new ConcurrentHashMap<>();

    private static final String[] INTERACTIVE_ARIA_ATTRS = {
            "aria-expanded", "aria-checked", "aria-selected",
            "aria-pressed", "aria-haspopup", "aria-controls",
            "aria-owns", "aria-activedescendant"
    };

    private static final String CANDIDATE_SELECTOR =
            "input, select, textarea, button, a, [role], [contenteditable='true'], " +
            "[onclick], [tabindex], [data-action], [data-toggle], [data-index], " +
            "[aria-haspopup], [aria-expanded], [aria-checked], [aria-pressed], [aria-selected], " +
            "[aria-controls], [aria-owns], [aria-activedescendant], " +
            ".el-menu-item, .el-submenu__title, .el-select, .el-button, .el-checkbox, .el-radio, .el-switch, " +
            ".ant-menu-item, .ant-menu-submenu-title, .ant-select, .ant-btn, .ant-checkbox, .ant-radio, .ant-switch, " +
            ".button, .dropdown-toggle, .nav-item, .sidebar-item, .menu-item";

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

    public TestExecutionResult executeSteps(String targetUrl, List<TestAction> steps) {
        return executeSteps(targetUrl, steps, "EDGE", false);
    }

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
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            result.setDetails(sw.toString());
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
        return result;
    }

    @Override
    public TestExecutionResult executeWithAutoPlan(String targetUrl, String taskDescription, Function<String, List<TestAction>> planGenerator, String browserName, boolean visual) {
        TestExecutionResult result = new TestExecutionResult();

        WebDriver driver = null;
        try {
            driver = createDriver(browserName, visual);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            
            String pageElements = collectPageElements(driver, targetUrl);
            log.info("Collected page elements before execution:\n{}", pageElements);

            List<TestAction> steps = planGenerator.apply(pageElements);
            if (steps == null || steps.isEmpty()) {
                throw new IllegalStateException("Generated test plan is empty");
            }

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
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            result.setDetails(sw.toString());
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
        return result;
    }

    private String collectPageElements(WebDriver driver, String targetUrl) {
        try {
            driver.get(targetUrl);
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

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

            log.info("Collected page elements:\n{}", sb.toString());
            return sb.toString();
        } catch (Exception ex) {
            log.error("Failed to collect page elements", ex);
            return "无法获取页面元素信息：" + ex.getMessage();
        }
    }

    private String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private String safeGetAttr(WebElement el, String attr) {
        try {
            String v = el.getAttribute(attr);
            return v != null ? v : "";
        } catch (Exception e) { return ""; }
    }

    private String safeGetText(WebElement el) {
        try {
            String t = el.getText();
            if (t == null || t.trim().isEmpty()) t = el.getAttribute("value");
            if (t == null) return "";
            t = t.trim();
            return t.length() > 80 ? t.substring(0, 80) + "..." : t;
        } catch (Exception e) { return ""; }
    }

    private WebDriver createDriver(String browserName, boolean visual) {
        String requested = (browserName == null || browserName.isBlank()) ? "EDGE" : browserName.toUpperCase();
        String fallback = "EDGE".equals(requested) ? "CHROME" : "EDGE";
        Exception lastError = null;

        for (String candidate : new String[]{requested, fallback}) {
            try {
                log.info("Trying to start {} driver...", candidate);
                WebDriver driver;
                if ("EDGE".equals(candidate)) {
                    driver = createEdgeDriver(visual);
                } else {
                    driver = createChromeDriver(visual);
                }
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

    private WebDriver createEdgeDriver(boolean visual) {
        EdgeOptions options = new EdgeOptions();
        if (!visual) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-allow-origins=*");

        String binary = locateBrowserBinary("EDGE");
        if (binary != null) {
            options.setBinary(binary);
            log.info("Using Edge binary: {}", binary);
        }
        return new EdgeDriver(options);
    }

    private WebDriver createChromeDriver(boolean visual) {
        ChromeOptions options = new ChromeOptions();
        if (!visual) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-allow-origins=*");

        String binary = locateBrowserBinary("CHROME");
        if (binary != null) {
            options.setBinary(binary);
            log.info("Using Chrome binary: {}", binary);
        }
        return new ChromeDriver(options);
    }

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

    private String findExisting(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank() && Files.exists(Paths.get(c))) {
                return c;
            }
        }
        return null;
    }

    private TestStepResult executeAction(WebDriver driver, TestAction action) {
        String description;
        try {
            TestActionType type = action.getType();
            switch (type) {
                case NAVIGATE:
                    String url = String.valueOf(action.getParameters().get("url"));
                    driver.get(url);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    description = "Navigate to " + url;
                    break;
                case CLICK:
                    WebElement clickElement = findElement(driver, action);
                    clickElement.click();
                    Object clickIndex = action.getParameters().get("index");
                    description = "Click element (index=" + clickIndex + ")";
                    break;
                case TYPE:
                    WebElement typeElement = findElement(driver, action);
                    Object text = action.getParameters().get("text");
                    typeElement.clear();
                    typeElement.sendKeys(text == null ? "" : String.valueOf(text));
                    Object typeIndex = action.getParameters().get("index");
                    description = "Type into element (index=" + typeIndex + "): " + text;
                    break;
                case ASSERT_TEXT:
                    WebElement assertElement = findElement(driver, action);
                    String actualText = assertElement.getText();
                    Object expectedValue = action.getParameters().getOrDefault("text", action.getParameters().get("expected"));
                    String expectedText = expectedValue == null ? "" : String.valueOf(expectedValue);
                    if (!actualText.contains(expectedText)) {
                        throw new IllegalStateException("Assertion failed for element (index=" + action.getParameters().get("index") + "): expected [" + expectedText + "] but found [" + actualText + "]");
                    }
                    Object assertIndex = action.getParameters().get("index");
                    description = "Assert text contains " + expectedText + " for element (index=" + assertIndex + ")";
                    break;
                case WAIT:
                    Object waitMsValue = action.getParameters().get("ms");
                    Object secondsValue = action.getParameters().get("seconds");
                    long sleepMillis;
                    if (waitMsValue instanceof Number) {
                        sleepMillis = ((Number) waitMsValue).longValue();
                    } else if (waitMsValue != null) {
                        sleepMillis = Long.parseLong(String.valueOf(waitMsValue));
                    } else if (secondsValue instanceof Number) {
                        sleepMillis = ((Number) secondsValue).longValue() * 1000L;
                    } else if (secondsValue != null) {
                        sleepMillis = Long.parseLong(String.valueOf(secondsValue)) * 1000L;
                    } else {
                        sleepMillis = 1000L;
                    }
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
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

    private String captureScreenshot(WebDriver driver) {
        try {
            if (driver instanceof TakesScreenshot takesScreenshot) {
                String base64 = takesScreenshot.getScreenshotAs(OutputType.BASE64);
                return "data:image/png;base64," + base64;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private WebElement findElement(WebDriver driver, TestAction action) {
        // 优先使用 index 参数（LLM 智能定位）
        Object indexObj = action.getParameters().get("index");
        if (indexObj instanceof Number) {
            int index = ((Number) indexObj).intValue();
            return findElementByIndex(driver, index);
        }
        
        String selector = String.valueOf(action.getParameters().get("selector"));
        if (selector == null || selector.isEmpty()) {
            throw new IllegalArgumentException("Selector or index must be provided");
        }
        selector = selector.trim();

        if (selector.startsWith("//") || selector.startsWith("(.") || selector.startsWith("xpath=")) {
            String xpath = selector.startsWith("xpath=") ? selector.substring("xpath=".length()) : selector;
            return driver.findElement(By.xpath(xpath));
        }

        if (selector.toLowerCase().startsWith("text=")) {
            String text = selector.substring(5).trim();
            String xpath = "//*[contains(normalize-space(string(.)), '" + escapeXPathText(text) + "')]";
            return driver.findElement(By.xpath(xpath));
        }

        if (selector.matches("(?i)^.+:has-text\\(['\"].+['\"]\\)$")) {
            int idx = selector.indexOf(":has-text(");
            String tag = selector.substring(0, idx).trim();
            String text = selector.substring(idx + 10, selector.length() - 1).trim();
            if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
                text = text.substring(1, text.length() - 1);
            }
            String xpath = "//" + tag + "[contains(normalize-space(string(.)), '" + escapeXPathText(text) + "')]";
            return driver.findElement(By.xpath(xpath));
        }

        // 支持 jQuery 风格的 :contains() 伪类选择器，例如：button:contains('登录')
        if (selector.contains(":contains(")) {
            int idx = selector.indexOf(":contains(");
            String tag = selector.substring(0, idx).trim();
            String text = selector.substring(idx + 10, selector.length() - 1).trim();
            if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
                text = text.substring(1, text.length() - 1);
            }
            String xpath = "//" + tag + "[contains(normalize-space(string(.)), '" + escapeXPathText(text) + "')]";
            log.info("Converted :contains() selector to XPath: {}", xpath);
            return driver.findElement(By.xpath(xpath));
        }

        return driver.findElement(By.cssSelector(selector));
    }

    /**
     * 根据 DOM 元素索引定位元素（从0开始计数）
     * 只匹配可交互元素：input, select, textarea, button, a
     */
    private WebElement findElementByIndex(WebDriver driver, int index) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jsResults = (List<Map<String, Object>>)
                ((JavascriptExecutor) driver).executeScript(JS_COLLECT);

        log.info("JS detected {} interactive elements on page", jsResults.size());
        for (int i = 0; i < jsResults.size(); i++) {
            Map<String, Object> item = jsResults.get(i);
            log.info("  [{}] <{}> text=\"{}\" id={} name={}",
                i, item.get("tag"), item.get("text"), item.get("id"), item.get("name"));
        }

        if (index < 0 || index >= jsResults.size()) {
            throw new NoSuchElementException("Element index " + index + " out of range [0, " + jsResults.size() + "). " +
                "Available interactive elements: " + jsResults.size());
        }

        Map<String, Object> target = jsResults.get(index);
        String tag = (String) target.get("tag");
        String text = (String) target.get("text");
        String id = (String) target.get("id");
        String name = (String) target.get("name");
        String type = (String) target.get("type");
        String placeholder = (String) target.get("ph");

        StringBuilder cssBuilder = new StringBuilder(tag);
        if (!id.isEmpty()) cssBuilder.append("#").append(id);
        if (!name.isEmpty()) cssBuilder.append("[name='").append(name).append("']");
        if (!type.isEmpty()) cssBuilder.append("[type='").append(type).append("']");
        if (!placeholder.isEmpty()) cssBuilder.append("[placeholder='").append(placeholder).append("']");

        List<WebElement> candidates = driver.findElements(By.cssSelector(cssBuilder.toString()));
        if (!candidates.isEmpty()) {
            for (WebElement c : candidates) {
                try {
                    if (c.isDisplayed()) {
                        log.info("Found element at index {} by CSS: {}", index, cssBuilder);
                        return c;
                    }
                } catch (Exception ignored) {}
            }
        }

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

    private String escapeXPathText(String text) {
        if (!text.contains("'")) {
            return text;
        }
        if (!text.contains("\"")) {
            return text;
        }
        String[] parts = text.split("'");
        StringBuilder builder = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(", '\'', ");
            }
            builder.append('"').append(parts[i]).append('"');
        }
        builder.append(")");
        return builder.toString();
    }

    @Override
    public AgentLoopResult runAgentLoop(String targetUrl, String taskDescription, String browserName, boolean visual) {
        return runAgentLoopWithSession(targetUrl, taskDescription, browserName, visual, null);
    }

    public AgentLoopResult runAgentLoopWithSession(String targetUrl, String taskDescription,
                                                    String browserName, boolean visual, String taskId) {
        return runAgentLoopWithSession(targetUrl, taskDescription, browserName, visual, taskId, null);
    }

    public AgentLoopResult runAgentLoopWithSession(String targetUrl, String taskDescription,
                                                    String browserName, boolean visual, String taskId,
                                                    AgentLoopCallback callback) {
        WebDriver driver = null;
        boolean keepDriverOpen = false;
        try {
            driver = createDriver(browserName, visual);
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            driver.get(targetUrl);
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            AgentLoopResult loopResult = agentLoopService.run(driver, targetUrl, taskDescription);

            if (loopResult.getNeedsInputPrompt() != null && taskId != null) {
                keepDriverOpen = true;
                log.info("Agent needs input for task {}, keeping browser open", taskId);
                AgentSession session = agentLoopService.createSession(taskId, driver, loopResult);
                activeSessions.put(taskId, session);

                if (callback != null) {
                    callback.onNeedsInput(loopResult);
                }

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
                AgentLoopResult resumeResult = agentLoopService.resumeFromSession(session);

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
                    resumeResult = agentLoopService.resumeFromSession(session);
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
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            err.setDetails(sw.toString());
            return err;
        } finally {
            if (driver != null && !keepDriverOpen) {
                try { driver.quit(); } catch (Exception ignored) {}
            }
        }
    }

    private List<TestStepResult> convertAgentStepsToTestSteps(List<com.gao.agent.model.AgentStepResult> agentSteps) {
        if (agentSteps == null || agentSteps.isEmpty()) {
            return new ArrayList<>();
        }
        List<TestStepResult> testSteps = new ArrayList<>();
        for (com.gao.agent.model.AgentStepResult agentStep : agentSteps) {
            TestStepResult testStep = new TestStepResult();
            
            String actionName = agentStep.getAction() != null ? agentStep.getAction().getAction().name() : "unknown";
            String summary = agentStep.getAction() != null ? agentStep.getAction().getSummary() : null;
            
            StringBuilder description = new StringBuilder();
            description.append("[").append(agentStep.getStepNumber()).append("] ").append(actionName);
            if (summary != null && !summary.isEmpty()) {
                description.append(" - ").append(summary);
            }
            testStep.setDescription(description.toString());
            
            testStep.setSuccess(agentStep.isSuccess());
            testStep.setScreenshotBase64(agentStep.getScreenshot());
            
            if (!agentStep.isSuccess() && agentStep.getMessage() != null) {
                testStep.setDetails("错误: " + agentStep.getMessage());
            }
            
            testSteps.add(testStep);
        }
        return testSteps;
    }

    public void closeSession(String taskId) {
        AgentSession session = activeSessions.remove(taskId);
        if (session != null) {
            session.closeDriver();
        }
    }

    public AgentSession getSession(String taskId) {
        return activeSessions.get(taskId);
    }
}
