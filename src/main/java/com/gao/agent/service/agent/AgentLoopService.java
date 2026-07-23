package com.gao.agent.service.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.AgentSession;
import com.gao.agent.model.AgentStepResult;
import com.gao.agent.model.AgentStreamEvent;
import com.gao.agent.model.BrowserState;
import com.gao.agent.model.ConversationMessage;
import com.gao.agent.service.llm.LargeModelService;

/**
 * Agent Loop 核心服务。
 * 实现"感知→思考→行动→反馈"循环：
 * <ol>
 *   <li>采集当前页面状态（BrowserState）</li>
 *   <li>将页面状态发送给 LLM，由 LLM 决策下一步动作（AgentAction）</li>
 *   <li>通过 ActionExecutor 执行浏览器操作</li>
 *   <li>将操作结果和新页面状态作为反馈，再次发给 LLM</li>
 *   <li>重复直到 LLM 返回 done / needs_input / 超过最大步数</li>
 * </ol>
 *
 * 同时负责：
 * <ul>
 *   <li>通过 SSE 实时推送执行进度到前端</li>
 *   <li>管理对话历史（textHistory / visionHistory），支持暂停后恢复</li>
 *   <li>在 done 和超时时截取最终页面截图</li>
 * </ul>
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);

    private final PageStateService pageStateService;
    private final ActionExecutor executor;
    private final LargeModelService llmService;

    /** 最大执行步数，超过后强制结束 */
    @Value("${agent.max-steps:30}")
    private int maxSteps;
    /** 每步操作后的等待时间（毫秒），让页面有时间响应 */
    @Value("${agent.wait-after-action-ms:1500}")
    private long waitMs;
    /** 是否启用 Vision 多模态模式（发送截图给 LLM） */
    @Value("${agent.vision-enabled:false}")
    private boolean visionEnabled;
    /** Vision 模式下每隔多少步发送一次截图 */
    @Value("${agent.vision-every-n-steps:1}")
    private int visionEveryNSteps;

    public AgentLoopService(PageStateService pageStateService, ActionExecutor executor, LargeModelService llmService) {
        this.pageStateService = pageStateService;
        this.executor = executor;
        this.llmService = llmService;
    }

    /**
     * 启动 Agent Loop（无 SSE 推送的简化版本）
     */
    public AgentLoopResult run(WebDriver driver, String targetUrl, String taskDescription) {
        return run(driver, targetUrl, taskDescription, null, null);
    }

    /**
     * 启动 Agent Loop。
     * 初始化对话历史和步骤列表，采集初始页面状态，然后进入循环。
     *
     * @param driver         Selenium 浏览器驱动
     * @param targetUrl      目标页面 URL
     * @param taskDescription 自然语言任务描述
     * @param taskId         任务 ID（用于 SSE 事件）
     * @param emitter        SSE 发射器（用于实时推送进度，可为 null）
     * @return Agent Loop 执行结果
     */
    public AgentLoopResult run(WebDriver driver, String targetUrl, String taskDescription, String taskId, SseEmitter emitter) {
        List<AgentStepResult> steps = new ArrayList<>();
        List<ConversationMessage> visionHistory = new ArrayList<>();
        List<Map<String, String>> textHistory = new ArrayList<>();

        // 初始化对话历史：系统提示词
        visionHistory.add(ConversationMessage.text("system", SYSTEM_PROMPT));
        textHistory.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        try {
            sendSse(emitter, AgentStreamEvent.thinking(taskId, 0, "任务开始，正在打开页面..."));
            BrowserState state = pageStateService.getBrowserState(driver);
            String screenshot = visionEnabled ? executor.screenshot(driver) : null;

            // 构建第一条用户消息：URL + 任务描述 + 页面元素列表
            String firstMsg = buildFirstUserMsg(targetUrl, taskDescription, state);
            if (useVision(screenshot, 1)) {
                visionHistory.add(ConversationMessage.withImage("user", firstMsg, screenshot));
                log.info("Step 1: sending text + screenshot to vision model");
            } else {
                visionHistory.add(ConversationMessage.text("user", firstMsg));
            }
            textHistory.add(Map.of("role", "user", "content", firstMsg));

            return runLoop(driver, visionHistory, textHistory, state, steps, 1, taskId, emitter);
        } catch (Exception e) {
            log.error("Agent loop crashed", e);
            sendSse(emitter, AgentStreamEvent.error(taskId, -1, "异常: " + e.getMessage()));
            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setMessage("异常: " + e.getMessage());
            result.setSteps(steps);
            return result;
        }
    }

    /**
     * 根据 AgentLoopResult 创建 AgentSession。
     * 用于任务暂停（needs_input）时保存运行时状态，以便后续恢复。
     */
    public AgentSession createSession(String taskId, WebDriver driver, AgentLoopResult loopResult) {
        return new AgentSession(taskId, driver,
                new ArrayList<>(loopResult.getConversationHistory()),
                new ArrayList<>(loopResult.getSteps()),
                loopResult.getLastBrowserState(),
                loopResult.getTotalSteps(),
                loopResult.getNeedsInputPrompt());
    }

    /** 恢复执行（无 SSE 推送的简化版本） */
    public AgentLoopResult resumeFromSession(AgentSession session) {
        return resumeFromSession(session, null);
    }

    /**
     * 从暂停的 Session 恢复 Agent Loop。
     * 将用户输入追加到对话历史中，然后继续执行循环。
     *
     * @param session 暂停时保存的 AgentSession（包含对话历史、页面状态等）
     * @param emitter SSE 发射器（恢复后重新绑定）
     * @return Agent Loop 执行结果
     */
    public AgentLoopResult resumeFromSession(AgentSession session, SseEmitter emitter) {
        String userInput = session.getUserInput();
        log.info("Resuming agent loop with user input: {}", userInput);

        List<ConversationMessage> visionHistory = new ArrayList<>();
        List<Map<String, String>> textHistory = new ArrayList<>();

        // 从 Session 中恢复之前的对话历史
        for (Map<String, String> m : session.getConversationHistory()) {
            textHistory.add(Map.of("role", m.get("role"), "content", m.get("content")));
            visionHistory.add(ConversationMessage.text(m.get("role"), m.get("content")));
        }

        List<AgentStepResult> steps = new ArrayList<>(session.getSteps());
        WebDriver driver = session.getDriver();
        BrowserState state = session.getLastState();
        String taskId = session.getTaskId();

        // 构建恢复消息：用户输入 + 当前页面状态
        String resumeMsg = "用户提供了以下信息：\n" + userInput +
                "\n\n请根据这个信息继续操作。当前页面状态如下：\n\n" +
                state.toLLMContext() +
                "\n\n️ 重要提醒：如果需要点击元素或输入文本，必须提供 index（元素编号）！" +
                "\n例如：{\"action\": \"click_element\", \"index\": 5, \"thinking\": \"点击登录按钮\"}" +
                "\n或者：{\"action\": \"input_text\", \"index\": 3, \"text\": \"验证码内容\", \"thinking\": \"输入验证码\"}" +
                "\n\n请继续决定下一步操作。返回 JSON 格式。";

        visionHistory.add(ConversationMessage.text("user", resumeMsg));
        textHistory.add(Map.of("role", "user", "content", resumeMsg));

        try {
            sendSse(emitter, AgentStreamEvent.thinking(taskId, steps.size() + 1, "收到用户输入，正在恢复执行..."));
            return runLoop(driver, visionHistory, textHistory, state, steps, steps.size() + 1, taskId, emitter);
        } catch (Exception e) {
            log.error("Agent loop resume crashed", e);
            sendSse(emitter, AgentStreamEvent.error(taskId, -1, "恢复执行异常: " + e.getMessage()));
            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setMessage("恢复执行异常: " + e.getMessage());
            result.setSteps(steps);
            return result;
        }
    }

    /**
     * Agent Loop 核心循环。
     * 每轮循环执行以下流程：
     * <ol>
     *   <li>调用 LLM 决策下一步动作</li>
     *   <li>如果动作是 done → 处理完成逻辑（含截图），返回结果</li>
     *   <li>如果动作是 needs_input → 暂停等待用户输入，返回结果</li>
     *   <li>通过 ActionExecutor 执行浏览器操作</li>
     *   <li>等待指定时间后重新采集页面状态</li>
     *   <li>构建反馈消息追加到对话历史</li>
     * </ol>
     *
     * @param driver        浏览器驱动
     * @param visionHistory Vision 模式对话历史（可携带图片）
     * @param textHistory   纯文本模式对话历史
     * @param state         当前页面状态
     * @param steps         已执行的步骤列表
     * @param startStep     起始步数（首次为 1，恢复时接续）
     * @param taskId        任务 ID
     * @param emitter       SSE 发射器
     * @return Agent Loop 执行结果
     */
    private AgentLoopResult runLoop(WebDriver driver, List<ConversationMessage> visionHistory,
                                     List<Map<String, String>> textHistory,
                                     BrowserState state, List<AgentStepResult> steps,
                                     int startStep, String taskId, SseEmitter emitter) {
        boolean useVisionMode = visionEnabled && llmService.supportsVision();

        for (int step = startStep; step <= maxSteps; step++) {
            log.info("━━━ Agent Loop Step {}/{} ━━━", step, maxSteps);
            sendSse(emitter, AgentStreamEvent.thinking(taskId, step, "正在分析页面状态..."));

            // ① 调用 LLM 决策下一步动作
            AgentAction action;
            try {
                if (useVisionMode) {
                    action = llmService.decideNextActionWithVision(visionHistory);
                } else {
                    action = llmService.decideNextAction(textHistory);
                }
            } catch (Exception e) {
                log.error("LLM failed at step {}", step, e);
                sendSse(emitter, AgentStreamEvent.error(taskId, step, "LLM调用失败: " + e.getMessage()));
                AgentLoopResult result = new AgentLoopResult();
                result.setSuccess(false);
                result.setMessage("LLM调用失败: " + e.getMessage());
                result.setSteps(steps);
                return result;
            }
            log.info("LLM → action={}, index={}, text={}", action.getAction(), action.getIndex(), action.getText());
            sendSse(emitter, AgentStreamEvent.thinking(taskId, step, action.getThinking() != null ? action.getThinking() : "正在决定下一步操作..."));

            // 记录本步执行前的状态快照
            AgentStepResult sr = new AgentStepResult();
            sr.setStepNumber(step);
            sr.setAction(action);
            sr.setStateBefore(state);
            sr.setScreenshot(executor.screenshot(driver));

            // ② 处理 done 动作：任务结束
            if (action.getAction() == AgentAction.ActionType.done) {
                AgentLoopResult doneResult = handleDone(action, sr, steps, taskId, emitter);
                if (doneResult != null) {
                    doneResult.setTotalSteps(step);
                    doneResult.setConversationHistory(new ArrayList<>(textHistory));
                    doneResult.setLastBrowserState(state);
                    // needs_input 转换时直接返回（不截取最终截图）
                    if (doneResult.getNeedsInputPrompt() != null) {
                        return doneResult;
                    }
                    // 任务真正结束时截取最终页面截图
                    String finalScreenshot = executor.screenshot(driver);
                    log.info("Final screenshot captured: {}", finalScreenshot != null ? finalScreenshot.substring(0, Math.min(50, finalScreenshot.length())) + "..." : "NULL");
                    sendSse(emitter, AgentStreamEvent.doneWithScreenshot(taskId, doneResult.isSuccess(), doneResult.getMessage(), finalScreenshot));
                    return doneResult;
                }
                continue;
            }

            // ③ 处理 needs_input 动作：暂停等待用户输入
            if (action.getAction() == AgentAction.ActionType.needs_input) {
                String prompt = action.getSummary() != null ? action.getSummary() : "需要用户输入";
                log.info("Agent needs user input: {}", prompt);
                sendSse(emitter, AgentStreamEvent.needsInput(taskId, step, prompt));
                sr.setSuccess(false);
                sr.setMessage(" 等待用户输入: " + prompt);
                steps.add(sr);

                AgentLoopResult result = new AgentLoopResult();
                result.setSuccess(false);
                result.setNeedsInputPrompt(prompt);
                result.setMessage("需要用户输入: " + prompt);
                result.setSteps(steps);
                result.setTotalSteps(step);
                result.setConversationHistory(new ArrayList<>(textHistory));
                result.setLastBrowserState(state);
                return result;
            }

            // ④ 执行浏览器操作
            String actionDesc = buildActionDescription(action);
            sendSse(emitter, AgentStreamEvent.actionStart(taskId, step, action.getAction().name(), action.getIndex(), actionDesc));
            
            ActionExecutor.ActionResult ar = execute(driver, action, state);
            sr.setSuccess(ar.success());
            sr.setMessage(ar.message());
            steps.add(sr);

            String stepScreenshot = sr.getScreenshot();
            String elementRectJson = buildElementRectJson(action, state);
            sendSse(emitter, AgentStreamEvent.actionCompleteWithScreenshot(taskId, step, ar.success(), ar.message(), stepScreenshot, elementRectJson));

            // ⑤ 等待页面响应
            try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

            // ⑥ 重新采集页面状态并构建反馈
            state = pageStateService.getBrowserState(driver);
            String feedback = buildFeedback(action, ar, state);

            // Vision 模式下将截图附加到反馈消息中
            String screenshot = visionEnabled ? executor.screenshot(driver) : null;
            if (useVisionMode && screenshot != null && useVision(screenshot, step)) {
                visionHistory.add(ConversationMessage.withImage("user", feedback, screenshot));
                log.info("Step {}: sending text + screenshot to vision model", step);
            } else {
                visionHistory.add(ConversationMessage.text("user", feedback));
            }
            textHistory.add(Map.of("role", "user", "content", feedback));

            if (!ar.success()) {
                log.warn("Step {} failed: {}", step, ar.message());
            }
        }

        // 超过最大步数，强制结束
        AgentLoopResult result = new AgentLoopResult();
        result.setSuccess(false);
        result.setMessage("超过最大步数 " + maxSteps + "，任务未完成");
        result.setSteps(steps);
        result.setConversationHistory(new ArrayList<>(textHistory));
        result.setLastBrowserState(state);
        String timeoutScreenshot = executor.screenshot(driver);
        sendSse(emitter, AgentStreamEvent.doneWithScreenshot(taskId, false, result.getMessage(), timeoutScreenshot));
        return result;
    }

    /** 判断当前步骤是否需要发送截图给 Vision 模型 */
    private boolean useVision(String screenshot, int step) {
        return screenshot != null && (step % visionEveryNSteps == 0);
    }

    /** 构建动作描述文本（用于 SSE 推送给前端展示） */
    private String buildActionDescription(AgentAction action) {
        StringBuilder sb = new StringBuilder();
        sb.append(action.getAction().name());
        if (action.getIndex() != null) {
            sb.append(" [").append(action.getIndex()).append("]");
        }
        if (action.getText() != null && !action.getText().isEmpty()) {
            sb.append(": ").append(action.getText());
        }
        return sb.toString();
    }

    /**
     * 构建操作元素的坐标矩形 JSON 字符串。
     * 从 BrowserState.selectorMap 中取出对应 index 的元素坐标，
     * 返回格式 {"x":0,"y":0,"w":100,"h":30,"index":5}，无坐标时返回 null。
     */
    private String buildElementRectJson(AgentAction action, BrowserState state) {
        if (action.getIndex() == null || state.getSelectorMap() == null) return null;
        BrowserState.ElementInfo info = state.getSelectorMap().get(action.getIndex());
        if (info == null) return null;
        return "{\"x\":" + info.getX() + ",\"y\":" + info.getY() +
               ",\"w\":" + info.getWidth() + ",\"h\":" + info.getHeight() +
               ",\"index\":" + action.getIndex() + "}";
    }

    /**
     * 发送 SSE 事件到前端。
     * 自动处理 emitter 为 null 和发送失败的情况，不会抛出异常中断 Agent Loop。
     */
    private void sendSse(SseEmitter emitter, AgentStreamEvent event) {
        if (emitter != null && event != null) {
            try {
                log.info("Sending SSE event: type={}, step={}, content={}", event.type(), event.step(), event.content());
                emitter.send(SseEmitter.event().name(event.type()).data(event));
                log.info("SSE event sent successfully: type={}", event.type());
            } catch (IOException e) {
                log.warn("Failed to send SSE event: type={}, error={}", event.type(), e.getMessage());
            }
        } else {
            log.warn("Cannot send SSE event: emitter={}, event={}", emitter == null ? "null" : "valid", event == null ? "null" : "valid");
        }
    }

    /**
     * 处理 done 动作。
     * 如果 LLM 返回 done + success=false 且 summary 中包含需要用户协助的关键词，
     * 则自动将 done 转换为 needs_input，让任务暂停等待用户输入而非直接结束。
     */
    private AgentLoopResult handleDone(AgentAction action, AgentStepResult sr, List<AgentStepResult> steps, String taskId, SseEmitter emitter) {
        boolean taskSuccess = inferSuccess(action);

        // 尝试将失败的 done 转换为 needs_input（如遇到验证码等场景）
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

            sendSse(emitter, AgentStreamEvent.needsInput(taskId, steps.size(), prompt));

            AgentLoopResult result = new AgentLoopResult();
            result.setSuccess(false);
            result.setNeedsInputPrompt(prompt);
            result.setMessage("需要用户输入: " + prompt);
            result.setSteps(steps);
            return result;
        }

        sr.setSuccess(taskSuccess);
        String summary = action.getSummary() != null && !action.getSummary().isEmpty() 
                ? action.getSummary() 
                : (taskSuccess ? "任务已完成" : "任务结束");
        sr.setMessage((taskSuccess ? "✅ 完成: " : "❌ 未完成: ") + summary);
        steps.add(sr);

        AgentLoopResult result = new AgentLoopResult();
        result.setSuccess(taskSuccess);
        result.setSummary(action.getSummary());
        result.setMessage((taskSuccess ? "任务成功" : "任务未完成") + "，共 " + steps.size() + " 步。原因：" + summary);
        result.setSteps(steps);
        return result;
    }

    /**
     * 检查 summary 中是否包含需要用户协助的关键词。
     * 用于将失败的 done 动作自动转换为 needs_input。
     */
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

    /**
     * 根据 AgentAction 执行对应的浏览器操作。
     * 使用 switch 表达式分发到 ActionExecutor 的对应方法。
     */
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

    /** 线程休眠，捕获 InterruptedException 并恢复中断标志 */
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 构建第一条用户消息：包含目标 URL、任务描述和当前页面元素列表 */
    private String buildFirstUserMsg(String targetUrl, String task, BrowserState state) {
        return "当前 URL: " + targetUrl + "\n任务: " + task + "\n\n" + state.toLLMContext() +
                "\n\n请根据上面的页面元素列表，决定下一步操作。\n" +
                "返回 JSON 格式：\n{\"action\": \"<类型>\", \"index\": <数字>, \"text\": \"<内容>\", \"thinking\": \"<你的思考>\"}";
    }

    /**
     * 构建操作反馈消息。
     * 包含上一步操作内容、执行结果、当前 URL 和最新页面元素列表，
     * 同时提醒 LLM 必须基于实际页面证据判断任务是否完成。
     */
    private String buildFeedback(AgentAction action, ActionExecutor.ActionResult ar, BrowserState state) {
        return "上一步操作: " + action.getAction() + (action.getIndex() != null ? " index=" + action.getIndex() : "") +
                "\n结果: " + (ar.success() ? "✅ 成功" : "❌ 失败") + " - " + ar.message() +
                "\n当前页面 URL: " + state.getUrl() +
                "\n\n" + state.toLLMContext() +
                "\n\n⚠️ 请根据上面实际的页面元素列表来判断任务是否真正完成。" +
                "如果页面没有出现任务要求的标志性内容（如登录后的用户名、搜索结果等），请继续操作而不是返回 done。" +
                "如果任务已完成，返回 action=done。";
    }

    /**
     * 从 AgentAction 推断任务是否成功。
     * 优先使用 LLM 显式设置的 success 字段；
     * 若未设置，则通过 summary 中的失败关键词（如"失败"、"无法"、"未完成"等）推断。
     */
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

    /**
     * Agent Loop 系统提示词。
     * 定义了 LLM 可用的动作类型、严格规则、输出格式，
     * 以及关于 done 动作的关键约束（必须有页面证据才能标记成功）。
     */
    static final String SYSTEM_PROMPT = """
你是一个自动测试前端UI组件Agent。你需要逐步操作网页来完成用户的任务。

每一步你会收到：当前页面的可交互元素列表。你需要返回一个 JSON 对象，包含下一步要执行的一个动作。

## 动作类型
- click_element: 点击元素，需要 index（元素编号）
- input_text: 在输入框中输入文本，需要 index 和 text
- scroll: 滚动页面，需要 pixels（正数向下，负数向上）
- go_to_url: 导航到 URL，需要 url
- go_back: 返回上一页
- wait: 等待毫秒，需要 waitMs
- done: 任务结束，需要 summary（任务总结，不超过100字）和 success（是否成功完成）
- needs_input: 需要用户提供信息才能继续（如验证码无法识别时），需要 summary（说明需要什么）

## 严格规则
1. 每次只返回 1 个动作！
2. 只使用页面元素列表中 [数字] 标注的 index，不要猜测！
3. 仔细观察元素标签名、placeholder、text 来判断应该操作哪个元素。
4. 如果页面有验证码，从页面文本中识别并填入。
5. 如果遇到弹窗、对话框，先处理弹窗再继续。
6. 导航后如果页面变化需要等待加载，用 wait。
7. 当无法自动识别验证码或遇到需要用户协助的情况时，返回 needs_input 动作。不要直接放弃！

## ⚠️ 关于 done 的关键规则（必须遵守）
8. 只有当页面元素列表中出现了明确的"任务已完成"证据时，才能返回 done + success=true。
   - 例如：任务要求登录，则必须在页面元素中看到"退出"、"个人中心"、"欢迎"等登录后的标志性元素。
   - 例如：任务要求搜索，则必须在页面元素中看到搜索结果列表。
   - **重要：如果任务包含多个步骤（如"登录并找到XXX"），必须完成所有步骤！**
     * "登录并找到张三的个人能力评价" = 先登录 + 再导航到张三的评价页面 + 确认页面上显示"张三"的信息
     * 仅登录成功是不够的，必须继续操作直到看到目标内容
9. 严禁假设操作成功！如果上一步操作的结果不明确，先用 wait 等待，然后检查页面元素是否变化。
10. 如果当前页面元素与操作前相比没有明显变化，说明操作可能未生效，应尝试其他方法。
11. done 的 summary 必须简洁，不超过 100 字！必须基于页面中实际看到的元素来描述结果。
    - **必须明确指出看到了什么证据证明任务完成**（如"页面标题显示'张三的个人能力评价'"）
12. **遇到错误提示（如"用户不存在"、"密码错误"、"登录失败"等）时，不要直接返回 done！**
    - 应该先检查是否填错了信息（如账号、密码）
    - 如果确认信息正确但仍失败，返回 needs_input 让用户确认
    - 只有在尝试所有可能方法后仍无法完成时，才返回 done + success=false

## 输出格式（纯 JSON，不要代码块）
{"action": "click_element", "index": 0, "thinking": "思考过程"}

或者（需要用户输入）：
{"action": "needs_input", "summary": "验证码无法自动识别，请用户提供验证码文本"}

或者（成功完成，必须有页面证据）：
{"action": "done", "success": true, "summary": "已成功登录，页面显示了用户个人中心"}
""";
}