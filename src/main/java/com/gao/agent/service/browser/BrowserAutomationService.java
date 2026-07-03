package com.gao.agent.service.browser;

import java.util.List;
import java.util.function.Function;

import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestExecutionResult;

public interface BrowserAutomationService {
    TestExecutionResult executeSteps(String targetUrl, List<TestAction> steps, String browserName, boolean visual);
    
    TestExecutionResult executeWithAutoPlan(String targetUrl, String taskDescription, Function<String, List<TestAction>> planGenerator, String browserName, boolean visual);

    AgentLoopResult runAgentLoop(String targetUrl, String taskDescription, String browserName, boolean visual);
    
    default AgentLoopResult runAgentLoopWithSession(String targetUrl, String taskDescription,
                                                     String browserName, boolean visual, String taskId) {
        return runAgentLoop(targetUrl, taskDescription, browserName, visual);
    }
    
    default AgentLoopResult runAgentLoopWithSession(String targetUrl, String taskDescription,
                                                     String browserName, boolean visual, String taskId,
                                                     AgentLoopCallback callback) {
        return runAgentLoop(targetUrl, taskDescription, browserName, visual);
    }
    
    default void closeSession(String taskId) {}
    
    default Object getSession(String taskId) { return null; }
}
