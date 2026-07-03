package com.gao.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.AgentSession;
import com.gao.agent.model.TaskSubmissionResponse;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestExecutionResult;
import com.gao.agent.model.TestTaskRequest;
import com.gao.agent.model.TestTaskResponse;
import com.gao.agent.model.TestTaskStatus;
import com.gao.agent.service.TestAutomationService;
import com.gao.agent.service.browser.BrowserAutomationService;
import com.gao.agent.service.llm.LargeModelService;

@Service
public class TestAutomationServiceImpl implements TestAutomationService {

    private static final Logger log = LoggerFactory.getLogger(TestAutomationServiceImpl.class);

    private final LargeModelService largeModelService;
    private final BrowserAutomationService browserAutomationService;
    private final AsyncTaskRunner asyncTaskRunner;
    private final Map<String, TestTaskResponse> taskStore = new ConcurrentHashMap<>();

    public TestAutomationServiceImpl(LargeModelService largeModelService,
                                     BrowserAutomationService browserAutomationService,
                                     AsyncTaskRunner asyncTaskRunner) {
        this.largeModelService = largeModelService;
        this.browserAutomationService = browserAutomationService;
        this.asyncTaskRunner = asyncTaskRunner;
    }

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

    @Override
    public TestTaskResponse getTask(String taskId) {
        return taskStore.get(taskId);
    }

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

        session.provideInput(userInput);

        return taskResponse;
    }

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

    private AgentSession findSession(String taskId) {
        if (browserAutomationService instanceof com.gao.agent.service.browser.SeleniumBrowserAutomationService impl) {
            return impl.getSession(taskId);
        }
        return null;
    }

    private void executeTask(String taskId, TestTaskRequest request) {
        TestTaskResponse taskResponse = taskStore.get(taskId);
        if (taskResponse == null) {
            log.warn("Task {} not found when attempting to execute", taskId);
            return;
        }
        taskResponse.setStatus(TestTaskStatus.RUNNING);
        log.info("Executing task {} targetUrl={} visual={} mode=AGENT_LOOP", taskId, request.getTargetUrl(), request.isVisual());
        try {
            List<TestAction> actions = request.getActions() != null && !request.getActions().isEmpty()
                    ? request.getActions()
                    : null;

            if (actions != null) {
                TestExecutionResult executionResult = browserAutomationService.executeSteps(
                        request.getTargetUrl(), actions, request.getBrowser().name(), request.isVisual());
                taskResponse.setResult(executionResult);
                taskResponse.setStatus(executionResult.isSuccess() ? TestTaskStatus.SUCCESS : TestTaskStatus.FAILED);
            } else {
                com.gao.agent.service.browser.AgentLoopCallback callback = null;
                if (browserAutomationService instanceof com.gao.agent.service.browser.SeleniumBrowserAutomationService) {
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
                if (callback != null && browserAutomationService instanceof com.gao.agent.service.browser.SeleniumBrowserAutomationService seleniumService) {
                    loopResult = seleniumService.runAgentLoopWithSession(
                            request.getTargetUrl(),
                            request.getTaskDescription(),
                            request.getBrowser().name(),
                            request.isVisual(),
                            taskId,
                            callback);
                } else {
                    loopResult = browserAutomationService.runAgentLoop(
                            request.getTargetUrl(),
                            request.getTaskDescription(),
                            request.getBrowser().name(),
                            request.isVisual());
                }

                TestExecutionResult executionResult = new TestExecutionResult();
                executionResult.setSuccess(loopResult.isSuccess());
                executionResult.setMessage(loopResult.getMessage() != null ? loopResult.getMessage() : loopResult.getSummary());
                executionResult.setDetails("Agent Loop: " + loopResult.getTotalSteps() + " steps");
                
                // 将 AgentStepResult 转换为 TestStepResult
                List<com.gao.agent.model.TestStepResult> testSteps = convertAgentStepsToTestSteps(loopResult.getSteps());
                executionResult.setSteps(testSteps);
                
                taskResponse.setResult(executionResult);

                if (loopResult.getNeedsInputPrompt() != null) {
                    taskResponse.setStatus(TestTaskStatus.WAITING_INPUT);
                    taskResponse.setNeedsInputPrompt(loopResult.getNeedsInputPrompt());
                } else {
                    taskResponse.setStatus(loopResult.isSuccess() ? TestTaskStatus.SUCCESS : TestTaskStatus.FAILED);
                }
            }

            log.info("Task {} completed with status {}", taskId, taskResponse.getStatus());
        } catch (Exception ex) {
            log.error("Task {} execution failed", taskId, ex);
            TestExecutionResult failedResult = new TestExecutionResult();
            failedResult.setSuccess(false);
            failedResult.setMessage("Execution exception: " + ex.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            ex.printStackTrace(pw);
            failedResult.setDetails(sw.toString());
            taskResponse.setResult(failedResult);
            taskResponse.setStatus(TestTaskStatus.FAILED);
        }
    }

    private List<com.gao.agent.model.TestStepResult> convertAgentStepsToTestSteps(List<com.gao.agent.model.AgentStepResult> agentSteps) {
        if (agentSteps == null || agentSteps.isEmpty()) {
            return new ArrayList<>();
        }
        List<com.gao.agent.model.TestStepResult> testSteps = new ArrayList<>();
        for (com.gao.agent.model.AgentStepResult agentStep : agentSteps) {
            com.gao.agent.model.TestStepResult testStep = new com.gao.agent.model.TestStepResult();
            
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
