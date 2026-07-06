package com.gao.agent.service.impl;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.AgentSession;
import com.gao.agent.model.AgentStepResult;
import com.gao.agent.model.AgentStreamEvent;
import com.gao.agent.model.TaskSubmissionResponse;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestExecutionResult;
import com.gao.agent.model.TestStepResult;
import com.gao.agent.model.TestTaskRequest;
import com.gao.agent.model.TestTaskResponse;
import com.gao.agent.model.TestTaskStatus;
import com.gao.agent.service.TestAutomationService;
import com.gao.agent.service.browser.AgentLoopCallback;
import com.gao.agent.service.browser.BrowserAutomationService;
import com.gao.agent.service.browser.SeleniumBrowserAutomationService;
import com.gao.agent.service.llm.LargeModelService;

/**
 * 测试自动化服务实现。
 * 作为 Controller 和底层浏览器自动化服务之间的业务编排层，负责：
 * <ul>
 *   <li>任务生命周期管理：提交 → 运行 → 等待输入 → 恢复/完成/失败</li>
 *   <li>异步任务调度：通过 AsyncTaskRunner 将任务提交到异步线程，HTTP 请求立即返回 taskId</li>
 *   <li>SSE 事件推送：注册 SseEmitter，等待 emitter 就绪后传递给 Agent Loop</li>
 *   <li>暂停/恢复协调：Agent 遇到 needs_input 时更新任务状态，用户提交输入后唤醒阻塞线程</li>
 *   <li>结果转换：将 AgentStepResult 列表转换为 TestStepResult 列表供前端展示</li>
 * </ul>
 */
@Service
public class TestAutomationServiceImpl implements TestAutomationService {

    private static final Logger log = LoggerFactory.getLogger(TestAutomationServiceImpl.class);
    /** 等待前端注册 SSE emitter 的最大时间（毫秒） */
    private static final int EMITTER_WAIT_MAX_MS = 5000;
    /** 轮询检查 emitter 是否已注册的间隔（毫秒） */
    private static final int EMITTER_WAIT_INTERVAL_MS = 500;

    private final LargeModelService largeModelService;
    private final BrowserAutomationService browserAutomationService;
    private final AsyncTaskRunner asyncTaskRunner;
    /** 任务存储：taskId → TestTaskResponse */
    private final Map<String, TestTaskResponse> taskStore = new ConcurrentHashMap<>();
    /** SSE emitter 存储：taskId → SseEmitter */
    private final Map<String, SseEmitter> emitterStore = new ConcurrentHashMap<>();

    public TestAutomationServiceImpl(LargeModelService largeModelService,
                                     BrowserAutomationService browserAutomationService,
                                     AsyncTaskRunner asyncTaskRunner) {
        this.largeModelService = largeModelService;
        this.browserAutomationService = browserAutomationService;
        this.asyncTaskRunner = asyncTaskRunner;
    }

    /**
     * 注册 SSE emitter。
     * 前端建立 SSE 连接后调用，将 emitter 绑定到 taskId。
     * 如果此时已有 AgentSession（恢复场景），同步设置 session 的 emitter。
     * emitter 完成/超时/出错时自动从存储中移除。
     */
    @Override
    public void registerEmitter(String taskId, SseEmitter emitter) {
        emitterStore.put(taskId, emitter);
        emitter.onCompletion(() -> emitterStore.remove(taskId));
        emitter.onTimeout(() -> emitterStore.remove(taskId));
        emitter.onError(e -> emitterStore.remove(taskId));
        
        AgentSession session = findSession(taskId);
        if (session != null) {
            session.setEmitter(emitter);
        }
    }

    /**
     * 提交测试任务。
     * 生成 taskId，创建 TestTaskResponse 存入 taskStore，
     * 通过 AsyncTaskRunner 异步执行任务，立即返回 taskId 和 PENDING 状态。
     */
    @Override
    public TaskSubmissionResponse submitTask(TestTaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        TestTaskResponse taskResponse = new TestTaskResponse();
        taskResponse.setTaskId(taskId);
        taskResponse.setRequest(request);
        taskResponse.setStatus(TestTaskStatus.PENDING);
        taskResponse.setResult(new TestExecutionResult());
        taskStore.put(taskId, taskResponse);
        log.info("Created task {} targetUrl={} description={}", taskId, request.getTargetUrl(), request.getTaskDescription());
        asyncTaskRunner.executeAsync(() -> executeTask(taskId, request));
        return new TaskSubmissionResponse(taskId, TestTaskStatus.PENDING);
    }

    /** 查询任务状态和结果 */
    @Override
    public TestTaskResponse getTask(String taskId) {
        return taskStore.get(taskId);
    }

    /**
     * 恢复暂停的任务。
     * 将用户输入传递给 AgentSession，唤醒阻塞等待的 Agent Loop 线程。
     * 仅在任务状态为 WAITING_INPUT 时有效。
     */
    @Override
    public TestTaskResponse resumeTask(String taskId, String userInput) {
        TestTaskResponse taskResponse = taskStore.get(taskId);
        if (taskResponse == null) {
            return null;
        }
        if (taskResponse.getStatus() != TestTaskStatus.WAITING_INPUT) {
            return taskResponse;
        }

        AgentSession session = findSession(taskId);
        if (session == null) {
            log.warn("No active session found for task {}", taskId);
            taskResponse.setStatus(TestTaskStatus.FAILED);
            taskResponse.setNeedsInputPrompt(null);
            return taskResponse;
        }

        log.info("Resuming task {} with user input: {}", taskId, userInput);
        
        taskResponse.setStatus(TestTaskStatus.RUNNING);
        taskResponse.setNeedsInputPrompt(null);

        TestExecutionResult runningResult = new TestExecutionResult();
        runningResult.setSuccess(false);
        runningResult.setMessage("正在恢复执行...");
        taskResponse.setResult(runningResult);

        // 唤醒 Agent Loop 中阻塞的 CountDownLatch
        session.provideInput(userInput);

        return taskResponse;
    }

    /** 取消任务：关闭 Session（释放浏览器资源），标记任务为 FAILED */
    @Override
    public TestTaskResponse cancelTask(String taskId) {
        TestTaskResponse taskResponse = taskStore.get(taskId);
        if (taskResponse == null) {
            return null;
        }
        browserAutomationService.closeSession(taskId);
        taskResponse.setStatus(TestTaskStatus.FAILED);
        taskResponse.setNeedsInputPrompt(null);
        TestExecutionResult failedResult = new TestExecutionResult();
        failedResult.setSuccess(false);
        failedResult.setMessage("用户取消了任务");
        taskResponse.setResult(failedResult);
        return taskResponse;
    }

    /** 查找指定任务的 AgentSession（委托给 BrowserAutomationService） */
    private AgentSession findSession(String taskId) {
        return browserAutomationService.getSession(taskId);
    }

    /**
     * 异步执行任务的核心方法。
     * 流程：
     * <ol>
     *   <li>更新任务状态为 RUNNING</li>
     *   <li>等待前端注册 SSE emitter（最多 5 秒）</li>
     *   <li>根据请求中是否有预定义 actions，选择执行模式：
     *       有 actions → executePredefinedActions（预生成计划模式）
     *       无 actions → executeAgentLoop（Agent Loop 模式）</li>
     * </ol>
     */
    private void executeTask(String taskId, TestTaskRequest request) {
        TestTaskResponse taskResponse = taskStore.get(taskId);
        if (taskResponse == null) {
            log.warn("Task {} not found when attempting to execute", taskId);
            return;
        }
        taskResponse.setStatus(TestTaskStatus.RUNNING);
        log.info("Executing task {} targetUrl={} visual={} mode=AGENT_LOOP", taskId, request.getTargetUrl(), request.isVisual());
        
        SseEmitter emitter = waitForEmitter(taskId);
        
        try {
            List<TestAction> actions = request.getActions() != null && !request.getActions().isEmpty()
                    ? request.getActions()
                    : null;

            if (actions != null) {
                executePredefinedActions(taskId, request, taskResponse, actions);
            } else {
                executeAgentLoop(taskId, request, taskResponse, emitter);
            }

            log.info("Task {} completed with status {}", taskId, taskResponse.getStatus());
        } catch (Exception ex) {
            handleTaskException(taskId, taskResponse, ex);
        }
    }

    /**
     * 等待前端注册 SSE emitter。
     * 由于 submitTask 异步执行，emitter 可能尚未注册，
     * 此处轮询等待最多 EMITTER_WAIT_MAX_MS 毫秒。
     */
    private SseEmitter waitForEmitter(String taskId) {
        SseEmitter emitter = emitterStore.get(taskId);
        if (emitter != null) return emitter;

        log.info("Emitter not yet registered for task {}, waiting up to {} ms...", taskId, EMITTER_WAIT_MAX_MS);
        long deadline = System.currentTimeMillis() + EMITTER_WAIT_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(EMITTER_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            emitter = emitterStore.get(taskId);
            if (emitter != null) {
                log.info("Emitter retrieved for task {}", taskId);
                return emitter;
            }
        }
        log.error("Emitter still null after waiting for task {}, SSE events will not be sent", taskId);
        return null;
    }

    /**
     * 执行预定义动作列表（预生成计划模式）。
     * 直接委托给 BrowserAutomationService.executeSteps()。
     */
    private void executePredefinedActions(String taskId, TestTaskRequest request,
                                          TestTaskResponse taskResponse, List<TestAction> actions) {
        TestExecutionResult executionResult = browserAutomationService.executeSteps(
                request.getTargetUrl(), actions, request.getBrowser().name(), request.isVisual());
        taskResponse.setResult(executionResult);
        taskResponse.setStatus(executionResult.isSuccess() ? TestTaskStatus.SUCCESS : TestTaskStatus.FAILED);
    }

    /**
     * 执行 Agent Loop 模式。
     * 创建 AgentLoopCallback（暂停时更新任务状态为 WAITING_INPUT），
     * 调用 SeleniumBrowserAutomationService.runAgentLoopWithSse() 执行。
     * 执行完成后根据结果设置任务状态（SUCCESS / FAILED / WAITING_INPUT）。
     */
    private void executeAgentLoop(String taskId, TestTaskRequest request,
                                   TestTaskResponse taskResponse, SseEmitter emitter) {
        // 创建暂停回调：Agent 遇到 needs_input 时更新任务状态
        AgentLoopCallback callback = null;
        if (browserAutomationService instanceof SeleniumBrowserAutomationService) {
            callback = (loopResult) -> {
                taskResponse.setStatus(TestTaskStatus.WAITING_INPUT);
                taskResponse.setNeedsInputPrompt(loopResult.getNeedsInputPrompt());
                
                TestExecutionResult executionResult = new TestExecutionResult();
                executionResult.setSuccess(false);
                executionResult.setMessage(loopResult.getMessage() != null ? loopResult.getMessage() : loopResult.getSummary());
                executionResult.setDetails("Agent Loop: " + loopResult.getTotalSteps() + " steps");
                taskResponse.setResult(executionResult);
                
                log.info("Task {} updated to WAITING_INPUT with prompt: {}", taskId, loopResult.getNeedsInputPrompt());
            };
        }

        AgentLoopResult loopResult;
        if (callback != null && browserAutomationService instanceof SeleniumBrowserAutomationService seleniumService) {
            // 有 SSE 和回调支持，使用完整功能版本
            loopResult = seleniumService.runAgentLoopWithSse(
                    request.getTargetUrl(),
                    request.getTaskDescription(),
                    request.getBrowser().name(),
                    request.isVisual(),
                    taskId,
                    callback,
                    emitter);
        } else {
            // 降级：无 SSE 推送
            loopResult = browserAutomationService.runAgentLoop(
                    request.getTargetUrl(),
                    request.getTaskDescription(),
                    request.getBrowser().name(),
                    request.isVisual());
        }

        // 将 AgentLoopResult 转换为 TestExecutionResult
        TestExecutionResult executionResult = new TestExecutionResult();
        executionResult.setSuccess(loopResult.isSuccess());
        executionResult.setMessage(loopResult.getMessage() != null ? loopResult.getMessage() : loopResult.getSummary());
        executionResult.setDetails("Agent Loop: " + loopResult.getTotalSteps() + " steps");
        executionResult.setSteps(convertAgentStepsToTestSteps(loopResult.getSteps()));
        taskResponse.setResult(executionResult);

        if (loopResult.getNeedsInputPrompt() != null) {
            taskResponse.setStatus(TestTaskStatus.WAITING_INPUT);
            taskResponse.setNeedsInputPrompt(loopResult.getNeedsInputPrompt());
        } else {
            taskResponse.setStatus(loopResult.isSuccess() ? TestTaskStatus.SUCCESS : TestTaskStatus.FAILED);
        }
    }

    /** 处理任务执行异常：记录日志、发送 SSE 错误事件、标记任务为 FAILED */
    private void handleTaskException(String taskId, TestTaskResponse taskResponse, Exception ex) {
        log.error("Task {} execution failed", taskId, ex);
        sendSseError(taskId, ex.getMessage());
        
        TestExecutionResult failedResult = new TestExecutionResult();
        failedResult.setSuccess(false);
        failedResult.setMessage("Execution exception: " + ex.getMessage());
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        failedResult.setDetails(sw.toString());
        taskResponse.setResult(failedResult);
        taskResponse.setStatus(TestTaskStatus.FAILED);
    }

    /** 通过 SSE 向前端推送错误事件 */
    private void sendSseError(String taskId, String errorMessage) {
        SseEmitter emitter = emitterStore.get(taskId);
        if (emitter != null) {
            try {
                AgentStreamEvent errorEvent = AgentStreamEvent.error(taskId, -1, errorMessage);
                emitter.send(SseEmitter.event().name(errorEvent.type()).data(errorEvent));
            } catch (Exception e) {
                log.warn("Failed to send SSE error event", e);
            }
        }
    }

    /**
     * 将 AgentStepResult 列表转换为 TestStepResult 列表。
     * AgentStepResult 包含 Agent Loop 每步的详细信息（动作类型、摘要、截图、成功状态），
     * 转换后的 TestStepResult 用于前端展示执行历史和截图。
     */
    private List<TestStepResult> convertAgentStepsToTestSteps(List<AgentStepResult> agentSteps) {
        if (agentSteps == null || agentSteps.isEmpty()) {
            return new ArrayList<>();
        }
        List<TestStepResult> testSteps = new ArrayList<>();
        for (AgentStepResult agentStep : agentSteps) {
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
}
