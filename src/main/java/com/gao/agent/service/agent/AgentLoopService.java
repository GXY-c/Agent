package com.gao.agent.service.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.AgentSession;
import com.gao.agent.model.AgentStepResult;
import com.gao.agent.model.BrowserState;
import com.gao.agent.service.llm.LargeModelService;

@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private final PageStateService pageStateService;
    private final ActionExecutor executor;
    private final LargeModelService llmService;

    @Value("${agent.max-steps:30}")
    private int maxSteps;
    @Value("${agent.wait-after-action-ms:1500}")
    private long waitMs;

    public AgentLoopService(PageStateService pageStateService, ActionExecutor executor, LargeModelService llmService) {
        this.pageStateService = pageStateService;
        this.executor = executor;
        this.llmService = llmService;
    }

    public AgentLoopResult run(WebDriver driver, String targetUrl, String taskDescription) {
        List<AgentStepResult> steps = new ArrayList<>();
        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        try {
            BrowserState state = pageStateService.getBrowserState(driver);
            history.add(Map.of("role", "user", "content", buildFirstUserMsg(targetUrl, taskDescription, state)));
            return runLoop(driver, history, state, steps, 1);
        } catch (Exception e) {
            log.error("Agent loop crashed", e);
            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setMessage("异常: " + e.getMessage());
            result.setSteps(steps);
            return result;
        }
    }

    public AgentSession createSession(String taskId, WebDriver driver, AgentLoopResult loopResult) {
        return new AgentSession(taskId, driver,
                new ArrayList<>(loopResult.getConversationHistory()),
                new ArrayList<>(loopResult.getSteps()),
                loopResult.getLastBrowserState(),
                loopResult.getTotalSteps(),
                loopResult.getNeedsInputPrompt());
    }

    public AgentLoopResult resumeFromSession(AgentSession session) {
        String userInput = session.getUserInput();
        log.info("Resuming agent loop with user input: {}", userInput);

        List<Map<String, String>> history = new ArrayList<>(session.getConversationHistory());
        List<AgentStepResult> steps = new ArrayList<>(session.getSteps());
        WebDriver driver = session.getDriver();
        BrowserState state = session.getLastState();

        history.add(Map.of("role", "user", "content",
                "用户提供了以下信息：\n" + userInput +
                "\n\n请根据这个信息继续操作。当前页面状态如下：\n\n" +
                state.toLLMContext() +
                "\n\n️ 重要提醒：如果需要点击元素或输入文本，必须提供 index（元素编号）！" +
                "\n例如：{\"action\": \"click_element\", \"index\": 5, \"thinking\": \"点击登录按钮\"}" +
                "\n或者：{\"action\": \"input_text\", \"index\": 3, \"text\": \"验证码内容\", \"thinking\": \"输入验证码\"}" +
                "\n\n请继续决定下一步操作。返回 JSON 格式。"));

        try {
            return runLoop(driver, history, state, steps, session.getStepCount() + 1);
        } catch (Exception e) {
            log.error("Agent loop resume crashed", e);
            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setMessage("恢复执行异常: " + e.getMessage());
            result.setSteps(steps);
            return result;
        }
    }

    private AgentLoopResult runLoop(WebDriver driver, List<Map<String, String>> history,
                                     BrowserState state, List<AgentStepResult> steps,
                                     int startStep) {
        for (int step = startStep; step <= maxSteps; step++) {
            log.info("━━━ Agent Loop Step {}/{} ━━━", step, maxSteps);

            AgentAction action;
            try {
                action = llmService.decideNextAction(history);
            } catch (Exception e) {
                log.error("LLM failed at step {}", step, e);
                AgentLoopResult result = new AgentLoopResult();
                result.setSuccess(false);
                result.setMessage("LLM调用失败: " + e.getMessage());
                result.setSteps(steps);
                return result;
            }
            log.info("LLM → action={}, index={}, text={}", action.getAction(), action.getIndex(), action.getText());

            AgentStepResult sr = new AgentStepResult();
            sr.setStepNumber(step);
            sr.setAction(action);
            sr.setStateBefore(state);
            sr.setScreenshot(executor.screenshot(driver));

            if (action.getAction() == AgentAction.ActionType.done) {
                AgentLoopResult doneResult = handleDone(action, sr, steps);
                if (doneResult != null) {
                    doneResult.setTotalSteps(step);
                    doneResult.setConversationHistory(new ArrayList<>(history));
                    doneResult.setLastBrowserState(state);
                    return doneResult;
                }
                continue;
            }

            if (action.getAction() == AgentAction.ActionType.needs_input) {
                String prompt = action.getSummary() != null ? action.getSummary() : "需要用户输入";
                log.info("Agent needs user input: {}", prompt);
                sr.setSuccess(false);
                sr.setMessage("⏸ 等待用户输入: " + prompt);
                steps.add(sr);

                AgentLoopResult result = new AgentLoopResult();
                result.setSuccess(false);
                result.setNeedsInputPrompt(prompt);
                result.setMessage("需要用户输入: " + prompt);
                result.setSteps(steps);
                result.setTotalSteps(step);
                result.setConversationHistory(new ArrayList<>(history));
                result.setLastBrowserState(state);
                return result;
            }

            ActionExecutor.ActionResult ar = execute(driver, action, state);
            sr.setSuccess(ar.success());
            sr.setMessage(ar.message());
            steps.add(sr);

            try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

            state = pageStateService.getBrowserState(driver);
            String feedback = buildFeedback(action, ar, state);
            history.add(Map.of("role", "user", "content", feedback));

            if (!ar.success()) {
                log.warn("Step {} failed: {}", step, ar.message());
            }
        }

        AgentLoopResult result = new AgentLoopResult();
        result.setSuccess(false);
        result.setMessage("超过最大步数 " + maxSteps + "，任务未完成");
        result.setSteps(steps);
        result.setConversationHistory(new ArrayList<>(history));
        result.setLastBrowserState(state);
        return result;
    }

    private AgentLoopResult handleDone(AgentAction action, AgentStepResult sr, List<AgentStepResult> steps) {
        boolean taskSuccess = inferSuccess(action);

        if (!taskSuccess && shouldAskForUserInput(action)) {
            String prompt = action.getSummary() != null ? action.getSummary() : "需要用户协助";
            log.info("Task blocked, converting done→needs_input: {}", prompt);

            AgentAction needsInput = new AgentAction();
            needsInput.setAction(AgentAction.ActionType.needs_input);
            needsInput.setSummary(prompt);
            sr.setAction(needsInput);
            sr.setSuccess(false);
            sr.setMessage("⏸ 等待用户输入: " + prompt);
            steps.add(sr);

            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setNeedsInputPrompt(prompt);
            result.setMessage("需要用户输入: " + prompt);
            result.setSteps(steps);
            return result;
        }

        sr.setSuccess(taskSuccess);
        sr.setMessage((taskSuccess ? "✅ 完成: " : "❌ 未完成: ") +
                (action.getSummary() != null ? action.getSummary() : "任务结束"));
        steps.add(sr);

        AgentLoopResult result = new AgentLoopResult();
        result.setSuccess(taskSuccess);
        result.setSummary(action.getSummary());
        result.setMessage((taskSuccess ? "任务成功" : "任务未完成") + "，共 " + steps.size() + " 步");
        result.setSteps(steps);
        return result;
    }

    private boolean shouldAskForUserInput(AgentAction action) {
        String summary = action.getSummary();
        if (summary == null || summary.isEmpty()) return false;
        String lower = summary.toLowerCase();
        String[] keywords = {"验证码", "captcha", "需要用户", "需要人工", "需要输入",
                "无法识别", "无法自动", "手动", "请提供", "请输入",
                "需要协助", "需要帮助", "需要确认"};
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private ActionExecutor.ActionResult execute(WebDriver driver, AgentAction action, BrowserState state) {
        return switch (action.getAction()) {
            case click_element -> {
                if (action.getIndex() == null) {
                    yield ActionExecutor.ActionResult.fail("click_element 动作缺少 index 参数");
                }
                yield executor.clickElement(state, action.getIndex(), driver);
            }
            case input_text    -> {
                if (action.getIndex() == null) {
                    yield ActionExecutor.ActionResult.fail("input_text 动作缺少 index 参数");
                }
                yield executor.inputText(state, action.getIndex(), action.getText(), driver);
            }
            case scroll        -> executor.scroll(driver, action.getPixels() != null ? action.getPixels() : 500);
            case go_to_url     -> executor.navigate(driver, action.getUrl());
            case go_back       -> executor.goBack(driver);
            case wait          -> { sleep(action.getWaitMs() != null ? action.getWaitMs() : 1000);
                yield ActionExecutor.ActionResult.ok("Waited " + (action.getWaitMs() != null ? action.getWaitMs() : 1000) + "ms"); }
            default            -> ActionExecutor.ActionResult.fail("Unknown action: " + action.getAction());
        };
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String buildFirstUserMsg(String targetUrl, String task, BrowserState state) {
        return "当前 URL: " + targetUrl + "\n任务: " + task + "\n\n" + state.toLLMContext() +
                "\n\n请根据上面的页面元素列表，决定下一步操作。\n" +
                "返回 JSON 格式：\n{\"action\": \"<类型>\", \"index\": <数字>, \"text\": \"<内容>\", \"thinking\": \"<你的思考>\"}";
    }

    private String buildFeedback(AgentAction action, ActionExecutor.ActionResult ar, BrowserState state) {
        return "上一步操作: " + action.getAction() + (action.getIndex() != null ? " index=" + action.getIndex() : "") +
                "\n结果: " + (ar.success() ? "✅ 成功" : "❌ 失败") + " - " + ar.message() +
                "\n\n" + state.toLLMContext() +
                "\n\n请根据最新页面状态决定下一步操作。如果任务已完成，返回 action=done。";
    }

    private boolean inferSuccess(AgentAction action) {
        if (action.getSuccess() != null) {
            return action.getSuccess();
        }
        String s = action.getSummary();
        if (s == null || s.isEmpty()) return true;
        String lower = s.toLowerCase();
        String[] failKeywords = {"失败", "无法", "未能", "未完成", "未成功", "未跳转",
                "不正确", "不匹配", "错误", "受阻", "放弃", "无法完成", "无法登录",
                "无法识别", "仍然", "仍停留", "未变化", "没有成功", "not success",
                "failed", "unable", "could not", "cannot", "解析失败", "非json",
                "非JSON", "parse error", "empty"};
        for (String kw : failKeywords) {
            if (lower.contains(kw)) {
                log.info("Inferred FAILED from keyword '{}': {}", kw, s.length() > 100 ? s.substring(0, 100) : s);
                return false;
            }
        }
        return true;
    }

    static final String SYSTEM_PROMPT = """
你是一个浏览器自动化 AI Agent。你需要逐步操作网页来完成用户的任务。

每一步你会收到：当前页面的可交互元素列表。你需要返回一个 JSON 对象，包含下一步要执行的一个动作。

## 动作类型
- click_element: 点击元素，需要 index（元素编号）
- input_text: 在输入框中输入文本，需要 index 和 text
- scroll: 滚动页面，需要 pixels（正数向下，负数向上）
- go_to_url: 导航到 URL，需要 url
- go_back: 返回上一页
- wait: 等待毫秒，需要 waitMs
- done: 任务结束，需要 summary（任务总结）和 success（是否成功完成）
- needs_input: 需要用户提供信息才能继续（如验证码无法识别时），需要 summary（说明需要什么）

## 严格规则
1. 每次只返回 1 个动作！
2. 只使用页面元素列表中 [数字] 标注的 index，不要猜测！
3. 仔细观察元素标签名、placeholder、text 来判断应该操作哪个元素。
4. 如果页面有验证码，从页面文本中识别并填入。
5. 如果遇到弹窗、对话框，先处理弹窗再继续。
6. 导航后如果页面变化需要等待加载，用 wait。
7. 当无法自动识别验证码或遇到需要用户协助的情况时，返回 needs_input 动作，在 summary 中说明需要什么。不要直接放弃！
8. 当任务成功完成时，返回 done 并设置 success=true。

## 输出格式（纯 JSON，不要代码块）
{"action": "click_element", "index": 0, "thinking": "思考过程"}

或者（需要用户输入）：
{"action": "needs_input", "summary": "验证码无法自动识别，请用户提供验证码文本"}

或者（成功完成）：
{"action": "done", "success": true, "summary": "已成功登录并跳转到首页"}
""";
}
