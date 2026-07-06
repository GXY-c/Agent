package com.gao.agent.model;

/**
 * 任务提交响应模型。
 * 前端调用 POST /api/automation/tasks 提交任务后，后端返回此响应，
 * 包含新生成的任务 ID 和初始状态，前端据此建立 SSE 连接以接收实时进度。
 */
public class TaskSubmissionResponse {
    /** 任务唯一标识（UUID），用于后续查询状态、建立 SSE 流、恢复/取消任务 */
    private String taskId;
    /** 任务初始状态（通常为 PENDING） */
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
