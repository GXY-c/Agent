package com.gao.agent.model;

public class TaskSubmissionResponse {
    private String taskId;
    private TestTaskStatus status;

    public TaskSubmissionResponse() {
    }

    public TaskSubmissionResponse(String taskId, TestTaskStatus status) {
        this.taskId = taskId;
        this.status = status;
    }

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
}
