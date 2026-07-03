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

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final PageStateService pageStateService;

    public ActionExecutor(PageStateService pageStateService) {
        this.pageStateService = pageStateService;
    }

    public ActionResult clickElement(BrowserState state, int index) {
        return clickElement(state, index, null);
    }

    public ActionResult clickElement(BrowserState state, int index, WebDriver driver) {
        if (driver == null) {
            return ActionResult.fail("WebDriver is required for JS-based click");
        }
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object result = js.executeScript(
                "var els=window.__agentEls||[];" +
                "var idx=arguments[0];" +
                "if(idx<0||idx>=els.length)return 'NOT_FOUND';" +
                "var el=els[idx];" +
                "if(typeof el.click==='function'){el.click();}" +
                "else{el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}" +
                "return el.tagName.toLowerCase();",
                index);

            if ("NOT_FOUND".equals(result)) {
                log.warn("clickElement [{}] not found in window.__agentEls", index);
                return ActionResult.fail("Element index " + index + " not found");
            }

            String tag = String.valueOf(result);
            BrowserState.ElementInfo info = state.getSelectorMap() != null ? state.getSelectorMap().get(index) : null;
            String desc = info != null ? info.getTag() : tag;
            log.info("clickElement [{}] → JS clicked <{}>", index, desc);
            return ActionResult.ok("Clicked [" + index + "] <" + desc + ">");
        } catch (StaleElementReferenceException e) {
            return retryClick(state, index, driver);
        } catch (Exception e) {
            log.error("clickElement [{}] failed", index, e);
            return ActionResult.fail("Click failed: " + e.getMessage());
        }
    }

    private ActionResult retryClick(BrowserState state, int index, WebDriver driver) {
        log.warn("clickElement [{}] stale, retrying after 300ms", index);
        try {
            Thread.sleep(300);
            pageStateService.getBrowserState(driver);
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object result = js.executeScript(
                "var els=window.__agentEls||[];" +
                "var idx=arguments[0];" +
                "if(idx<0||idx>=els.length)return 'NOT_FOUND';" +
                "var el=els[idx];" +
                "if(typeof el.click==='function'){el.click();}" +
                "else{el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}" +
                "return el.tagName.toLowerCase();",
                index);

            if ("NOT_FOUND".equals(result)) {
                return ActionResult.fail("Element index " + index + " not found after retry");
            }

            BrowserState.ElementInfo info = state.getSelectorMap() != null ? state.getSelectorMap().get(index) : null;
            String desc = info != null ? info.getTag() : String.valueOf(result);
            log.info("clickElement [{}] → JS clicked on retry <{}>", index, desc);
            return ActionResult.ok("Clicked [" + index + "] <" + desc + "> (retry)");
        } catch (Exception retryEx) {
            log.error("clickElement [{}] retry failed", index, retryEx);
            return ActionResult.fail("Click failed after retry: " + retryEx.getMessage());
        }
    }

    public ActionResult inputText(BrowserState state, int index, String text) {
        return inputText(state, index, text, null);
    }

    public ActionResult inputText(BrowserState state, int index, String text, WebDriver driver) {
        if (driver == null) {
            return ActionResult.fail("WebDriver is required for JS-based input");
        }
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object result = js.executeScript(
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
                "return el.tagName.toLowerCase();",
                index, text);

            if ("NOT_FOUND".equals(result)) {
                log.warn("inputText [{}] not found in window.__agentEls", index);
                return ActionResult.fail("Element index " + index + " not found");
            }

            String tag = String.valueOf(result);
            BrowserState.ElementInfo info = state.getSelectorMap() != null ? state.getSelectorMap().get(index) : null;
            String desc = info != null ? info.getTag() : tag;
            log.info("inputText [{}] → JS typed '{}' into <{}>", index, text, desc);
            return ActionResult.ok("Input '" + text + "' into [" + index + "] <" + desc + ">");
        } catch (StaleElementReferenceException e) {
            return retryInput(state, index, text, driver);
        } catch (Exception e) {
            log.error("inputText [{}] failed", index, e);
            return ActionResult.fail("Input failed: " + e.getMessage());
        }
    }

    private ActionResult retryInput(BrowserState state, int index, String text, WebDriver driver) {
        log.warn("inputText [{}] stale, retrying after 300ms", index);
        try {
            Thread.sleep(300);
            pageStateService.getBrowserState(driver);
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object result = js.executeScript(
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
                "return el.tagName.toLowerCase();",
                index, text);

            if ("NOT_FOUND".equals(result)) {
                return ActionResult.fail("Element index " + index + " not found after retry");
            }

            log.info("inputText [{}] → JS typed '{}' on retry", index, text);
            return ActionResult.ok("Input '" + text + "' into [" + index + "] (retry)");
        } catch (Exception retryEx) {
            log.error("inputText [{}] retry failed", index, retryEx);
            return ActionResult.fail("Input failed after retry: " + retryEx.getMessage());
        }
    }

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

    public ActionResult navigate(WebDriver driver, String url) {
        try {
            driver.get(url);
            return ActionResult.ok("Navigated to " + url);
        } catch (Exception e) {
            return ActionResult.fail("Navigation failed: " + e.getMessage());
        }
    }

    public ActionResult goBack(WebDriver driver) {
        try {
            driver.navigate().back();
            return ActionResult.ok("Navigated back");
        } catch (Exception e) {
            return ActionResult.fail("Go back failed: " + e.getMessage());
        }
    }

    public String screenshot(WebDriver driver) {
        try {
            if (driver instanceof TakesScreenshot ts)
                return "data:image/png;base64," + ts.getScreenshotAs(OutputType.BASE64);
        } catch (Exception ignored) {}
        return null;
    }

    public record ActionResult(boolean success, String message) {
        public static ActionResult ok(String msg) { return new ActionResult(true, msg); }
        public static ActionResult fail(String msg) { return new ActionResult(false, msg); }
    }
}
