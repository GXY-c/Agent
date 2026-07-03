package com.gao.agent.service.agent;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gao.agent.model.BrowserState;

@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    public ActionResult clickElement(BrowserState state, int index) {
        try {
            WebElement el = state.getElement(index);
            if (el == null) return ActionResult.fail("Element index " + index + " not found in state");
            el.click();
            log.info("clickElement [{}] → clicked <{}>", index, el.getTagName());
            return ActionResult.ok("Clicked [" + index + "] <" + el.getTagName() + ">");
        } catch (Exception e) {
            log.error("clickElement [{}] failed", index, e);
            return ActionResult.fail("Click failed: " + e.getMessage());
        }
    }

    public ActionResult inputText(BrowserState state, int index, String text) {
        try {
            WebElement el = state.getElement(index);
            if (el == null) return ActionResult.fail("Element index " + index + " not found in state");
            el.clear();
            el.sendKeys(text);
            log.info("inputText [{}] → typed '{}' into <{}>", index, text, el.getTagName());
            return ActionResult.ok("Input '" + text + "' into [" + index + "] <" + el.getTagName() + ">");
        } catch (Exception e) {
            log.error("inputText [{}] failed", index, e);
            return ActionResult.fail("Input failed: " + e.getMessage());
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
