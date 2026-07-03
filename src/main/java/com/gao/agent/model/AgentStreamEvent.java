package com.gao.agent.model;

public record AgentStreamEvent(
        String type,        // thinking, action_start, action_complete, needs_input, done, error
        String taskId,
        int step,
        String content,     // AI 思考内容或操作描述
        String action,      // 动作类型（可选）
        Integer index,      // 元素索引（可选）
        boolean success,    // 是否成功（可选）
        long timestamp
) {
    public static AgentStreamEvent thinking(String taskId, int step, String content) {
        return new AgentStreamEvent("thinking", taskId, step, content, null, null, true, System.currentTimeMillis());
    }

    public static AgentStreamEvent actionStart(String taskId, int step, String action, Integer index, String description) {
        return new AgentStreamEvent("action_start", taskId, step, description, action, index, true, System.currentTimeMillis());
    }

    public static AgentStreamEvent actionComplete(String taskId, int step, boolean success, String message) {
        return new AgentStreamEvent("action_complete", taskId, step, message, null, null, success, System.currentTimeMillis());
    }

    public static AgentStreamEvent needsInput(String taskId, int step, String prompt) {
        return new AgentStreamEvent("needs_input", taskId, step, prompt, null, null, false, System.currentTimeMillis());
    }

    public static AgentStreamEvent done(String taskId, boolean success, String message) {
        return new AgentStreamEvent("done", taskId, -1, message, null, null, success, System.currentTimeMillis());
    }

    public static AgentStreamEvent error(String taskId, int step, String errorMessage) {
        return new AgentStreamEvent("error", taskId, step, errorMessage, null, null, false, System.currentTimeMillis());
    }
}
