package com.gao.agent.model;

/**
 * 测试任务响应模型。
 * 用于 GET /api/automation/tasks/{taskId} 查询任务状态时返回，
 * 包含任务的完整信息：当前状态、原始请求、执行结果、等待输入提示等。
 */
public class TestTaskResponse {
    /** 任务唯一标识（UUID） */
    private String taskId;
    /** 任务当前状态（PENDING / RUNNING / SUCCESS / FAILED / WAITING_INPUT） */
    private TestTaskStatus status;
    /** 原始任务请求（包含目标 URL、任务描述等） */
    private TestTaskRequest request;
    /** 测试执行结果（任务完成后填充，包含每步详情） */
    private TestExecutionResult result;
    /** 需要用户输入时的提示文本（仅 WAITING_INPUT 状态时有值） */
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
