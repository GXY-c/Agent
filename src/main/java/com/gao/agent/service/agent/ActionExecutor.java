package com.gao.agent.service.agent;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gao.agent.model.BrowserState;

/**
 * 浏览器动作执行器。
 * 负责将 Agent Loop 中 LLM 决策的动作（AgentAction）转化为实际的浏览器操作。
 * 所有点击和输入操作均通过 JavaScript 驱动（而非 Selenium 原生 API），
 * 以兼容各类 UI 框架（如 Element UI、Ant Design 等）的自定义组件。
 *
 * 核心设计：
 * <ul>
 *   <li>通过 window.__agentEls 数组保存页面元素引用，用编号（index）定位元素</li>
 *   <li>遇到 StaleElementReferenceException 时自动重试（等待 300ms 后重新采集页面状态再执行）</li>
 *   <li>所有操作返回 ActionResult record，统一封装成功/失败状态和描述消息</li>
 * </ul>
 */
@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    /**
     * JS 点击脚本。
     * 从 window.__agentEls 数组中按 index 取出元素，
     * 优先调用原生 click()，不支持时 fallback 到 dispatchEvent(MouseEvent)。
     * 返回元素标签名（小写），元素不存在时返回 "NOT_FOUND"。
     */
    private static final String JS_CLICK =
        "var els=window.__agentEls||[];" +
        "var idx=arguments[0];" +
        "if(idx<0||idx>=els.length)return 'NOT_FOUND';" +
        "var el=els[idx];" +
        "if(typeof el.click==='function'){el.click();}" +
        "else{el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}" +
        "return el.tagName.toLowerCase();";

    /**
     * JS 输入脚本。
     * 从 window.__agentEls 数组中按 index 取出元素，
     * 根据元素类型（SELECT / contentEditable / INPUT / TEXTAREA）选择对应的赋值方式，
     * 赋值后触发 input 和 change 事件以通知 UI 框架更新。
     * 返回元素标签名（小写），元素不存在时返回 "NOT_FOUND"。
     */
    private static final String JS_INPUT =
        "var els=window.__agentEls||[];" +
        "var idx=arguments[0],text=arguments[1];" +
        "if(idx<0||idx>=els.length)return 'NOT_FOUND';" +
        "var el=els[idx];" +
        "el.focus();" +
        "if(el.tagName==='SELECT'){el.value=text;}" +
        "else if(el.isContentEditable){el.textContent=text;}" +
        "else if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){el.value=text;}" +
        "else{el.textContent=text;}" +
        "el.dispatchEvent(new Event('input',{bubbles:true}));" +
        "el.dispatchEvent(new Event('change',{bubbles:true}));" +
        "return el.tagName.toLowerCase();";

    private final PageStateService pageStateService;

    public ActionExecutor(PageStateService pageStateService) {
        this.pageStateService = pageStateService;
    }

    /** 点击元素（无 driver 的重载，仅用于兼容） */
    public ActionResult clickElement(BrowserState state, int index) {
        return clickElement(state, index, null);
    }

    /**
     * 点击指定编号的页面元素。
     * 通过 JS 脚本执行点击，遇到 StaleElementReferenceException 时自动重试一次。
     *
     * @param state  当前页面状态（包含元素编号映射）
     * @param index  元素在 __agentEls 数组中的编号
     * @param driver Selenium WebDriver 实例
     * @return 操作结果
     */
    public ActionResult clickElement(BrowserState state, int index, WebDriver driver) {
        if (driver == null) {
            return ActionResult.fail("WebDriver is required for JS-based click");
        }
        try {
            return doClick(driver, state, index, false);
        } catch (StaleElementReferenceException e) {
            return retryClick(state, index, driver);
        } catch (Exception e) {
            log.error("clickElement [{}] failed", index, e);
            return ActionResult.fail("Click failed: " + e.getMessage());
        }
    }

    /** 执行 JS 点击并返回结果 */
    private ActionResult doClick(WebDriver driver, BrowserState state, int index, boolean isRetry) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(JS_CLICK, index);

        if ("NOT_FOUND".equals(result)) {
            return ActionResult.fail("Element index " + index + (isRetry ? " not found after retry" : " not found"));
        }

        String tag = String.valueOf(result);
        BrowserState.ElementInfo info = state.getSelectorMap() != null ? state.getSelectorMap().get(index) : null;
        String desc = info != null ? info.getTag() : tag;
        log.info("clickElement [{}] → JS clicked <{}>{}", index, desc, isRetry ? " (retry)" : "");
        return ActionResult.ok("Clicked [" + index + "] <" + desc + ">" + (isRetry ? " (retry)" : ""));
    }

    /** 点击失败后等待 300ms，重新采集页面状态再重试一次 */
    private ActionResult retryClick(BrowserState state, int index, WebDriver driver) {
        log.warn("clickElement [{}] stale, retrying after 300ms", index);
        try {
            Thread.sleep(300);
            pageStateService.getBrowserState(driver);
            return doClick(driver, state, index, true);
        } catch (Exception retryEx) {
            log.error("clickElement [{}] retry failed", index, retryEx);
            return ActionResult.fail("Click failed after retry: " + retryEx.getMessage());
        }
    }

    /** 输入文本（无 driver 的重载，仅用于兼容） */
    public ActionResult inputText(BrowserState state, int index, String text) {
        return inputText(state, index, text, null);
    }

    /**
     * 在指定编号的页面元素中输入文本。
     * 通过 JS 脚本执行输入，自动处理不同元素类型（input/select/contentEditable）的差异，
     * 遇到 StaleElementReferenceException 时自动重试一次。
     *
     * @param state  当前页面状态
     * @param index  元素编号
     * @param text   要输入的文本内容
     * @param driver Selenium WebDriver 实例
     * @return 操作结果
     */
    public ActionResult inputText(BrowserState state, int index, String text, WebDriver driver) {
        if (driver == null) {
            return ActionResult.fail("WebDriver is required for JS-based input");
        }
        try {
            return doInput(driver, state, index, text, false);
        } catch (StaleElementReferenceException e) {
            return retryInput(state, index, text, driver);
        } catch (Exception e) {
            log.error("inputText [{}] failed", index, e);
            return ActionResult.fail("Input failed: " + e.getMessage());
        }
    }

    /** 执行 JS 输入并返回结果 */
    private ActionResult doInput(WebDriver driver, BrowserState state, int index, String text, boolean isRetry) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(JS_INPUT, index, text);

        if ("NOT_FOUND".equals(result)) {
            return ActionResult.fail("Element index " + index + (isRetry ? " not found after retry" : " not found"));
        }

        String tag = String.valueOf(result);
        BrowserState.ElementInfo info = state.getSelectorMap() != null ? state.getSelectorMap().get(index) : null;
        String desc = info != null ? info.getTag() : tag;
        log.info("inputText [{}] → JS typed '{}' into <{}>{}", index, text, desc, isRetry ? " (retry)" : "");
        return ActionResult.ok("Input '" + text + "' into [" + index + "] <" + desc + ">" + (isRetry ? " (retry)" : ""));
    }

    /** 输入失败后等待 300ms，重新采集页面状态再重试一次 */
    private ActionResult retryInput(BrowserState state, int index, String text, WebDriver driver) {
        log.warn("inputText [{}] stale, retrying after 300ms", index);
        try {
            Thread.sleep(300);
            pageStateService.getBrowserState(driver);
            return doInput(driver, state, index, text, true);
        } catch (Exception retryEx) {
            log.error("inputText [{}] retry failed", index, retryEx);
            return ActionResult.fail("Input failed after retry: " + retryEx.getMessage());
        }
    }

    /**
     * 滚动页面。
     *
     * @param driver WebDriver 实例
     * @param pixels 滚动像素数（正数向下，负数向上）
     * @return 操作结果
     */
    public ActionResult scroll(WebDriver driver, int pixels) {
        try {
            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0," + pixels + ")");
            log.info("scroll {}px", pixels);
            return ActionResult.ok("Scrolled " + pixels + "px");
        } catch (Exception e) {
            log.error("scroll failed", e);
            return ActionResult.fail("Scroll failed: " + e.getMessage());
        }
    }

    /**
     * 导航到指定 URL。
     *
     * @param driver WebDriver 实例
     * @param url    目标 URL（必须包含 http:// 或 https:// 协议前缀）
     * @return 操作结果
     */
    public ActionResult navigate(WebDriver driver, String url) {
        try {
            driver.get(url);
            return ActionResult.ok("Navigated to " + url);
        } catch (Exception e) {
            return ActionResult.fail("Navigation failed: " + e.getMessage());
        }
    }

    /**
     * 浏览器后退到上一页。
     *
     * @param driver WebDriver 实例
     * @return 操作结果
     */
    public ActionResult goBack(WebDriver driver) {
        try {
            driver.navigate().back();
            return ActionResult.ok("Navigated back");
        } catch (Exception e) {
            return ActionResult.fail("Go back failed: " + e.getMessage());
        }
    }

    /**
     * 截取当前页面的屏幕截图。
     *
     * @param driver WebDriver 实例
     * @return base64 编码的 PNG 截图（data:image/png;base64,... 格式），失败时返回 null
     */
    public String screenshot(WebDriver driver) {
        try {
            if (driver instanceof TakesScreenshot ts)
                return "data:image/png;base64," + ts.getScreenshotAs(OutputType.BASE64);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 操作结果 record。
     * 统一封装浏览器操作的执行结果，包含成功/失败状态和描述消息。
     *
     * @param success 是否执行成功
     * @param message 结果描述（如 "Clicked [3] <button>" 或 "Element index 5 not found"）
     */
    public record ActionResult(boolean success, String message) {
        /** 创建成功结果 */
        public static ActionResult ok(String msg) { return new ActionResult(true, msg); }
        /** 创建失败结果 */
        public static ActionResult fail(String msg) { return new ActionResult(false, msg); }
    }
}
