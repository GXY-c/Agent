package com.gao.agent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gao.agent.model.TaskSubmissionResponse;
import com.gao.agent.model.TestTaskRequest;
import com.gao.agent.model.TestTaskResponse;

public interface TestAutomationService {
    TaskSubmissionResponse submitTask(TestTaskRequest request);
    TestTaskResponse getTask(String taskId);
    TestTaskResponse resumeTask(String taskId, String userInput);
    TestTaskResponse cancelTask(String taskId);
    void registerEmitter(String taskId, SseEmitter emitter);
}
