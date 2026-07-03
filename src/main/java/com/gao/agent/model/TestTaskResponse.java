package com.gao.agent.model;

public class TestTaskResponse {
    private String taskId;
    private TestTaskStatus status;
    private TestTaskRequest request;
    private TestExecutionResult result;
    private String needsInputPrompt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public TestTaskStatus getStatus() {
        return status;
    }

    public void setStatus(TestTaskStatus status) {
        this.status = status;
    }

    public TestTaskRequest getRequest() {
        return request;
    }

    public void setRequest(TestTaskRequest request) {
        this.request = request;
    }

    public TestExecutionResult getResult() {
        return result;
    }

    public void setResult(TestExecutionResult result) {
        this.result = result;
    }

    public String getNeedsInputPrompt() { return needsInputPrompt; }
    public void setNeedsInputPrompt(String needsInputPrompt) { this.needsInputPrompt = needsInputPrompt; }
}
