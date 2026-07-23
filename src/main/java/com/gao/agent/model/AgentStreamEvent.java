package com.gao.agent.model;

/**
 * SSE 实时流事件模型（Java record）。
 * 通过 SseEmitter 向前端推送 Agent Loop 执行过程中的各类事件，
 * 前端根据 type 字段区分事件类型并渲染对应的 UI 展示。
 *
 * 事件类型（type）：
 * <ul>
 *   <li>thinking       — AI 正在思考/分析页面状态</li>
 *   <li>action_start   — 开始执行某个浏览器操作</li>
 *   <li>action_complete — 浏览器操作执行完毕（成功/失败）</li>
 *   <li>needs_input    — 需要用户输入（如验证码），任务暂停</li>
 *   <li>done           — 任务结束（成功或失败），可携带最终截图</li>
 *   <li>error          — 发生异常错误，任务终止</li>
 * </ul>
 *
 * @param type       事件类型标识
 * @param taskId     所属任务 ID
 * @param step       当前步骤编号（done 事件为 -1）
 * @param content    事件内容（思考文本、操作描述、结果消息等）
 * @param action     操作类型名称（仅 action_start 时使用，如 "click_element"）
 * @param index      目标元素编号（仅 action_start 时使用）
 * @param success    是否成功
 * @param screenshot 页面截图 base64 数据（done 事件携带最终截图，action_complete 事件携带该步操作前截图）
 * @param elementRect 操作元素的坐标矩形 JSON（仅 action_complete 时使用，格式 {"x":0,"y":0,"w":100,"h":30,"index":5}）
 * @param timestamp  事件时间戳（毫秒）
 */
public record AgentStreamEvent(
        String type,
        String taskId,
        int step,
        String content,
        String action,
        Integer index,
        boolean success,
        String screenshot,
        String elementRect,
        long timestamp
) {
    /** AI 正在思考，content 为思考描述文本 */
    public static AgentStreamEvent thinking(String taskId, int step, String content) {
        return new AgentStreamEvent("thinking", taskId, step, content, null, null, true, null, null, System.currentTimeMillis());
    }

    /** 开始执行浏览器操作，description 为操作的人类可读描述 */
    public static AgentStreamEvent actionStart(String taskId, int step, String action, Integer index, String description) {
        return new AgentStreamEvent("action_start", taskId, step, description, action, index, true, null, null, System.currentTimeMillis());
    }

    /** 浏览器操作执行完毕（无截图的兼容版本） */
    public static AgentStreamEvent actionComplete(String taskId, int step, boolean success, String message) {
        return new AgentStreamEvent("action_complete", taskId, step, message, null, null, success, null, null, System.currentTimeMillis());
    }

    /** 浏览器操作执行完毕，携带该步操作前截图和操作元素坐标矩形 */
    public static AgentStreamEvent actionCompleteWithScreenshot(String taskId, int step, boolean success, String message, String screenshot, String elementRect) {
        return new AgentStreamEvent("action_complete", taskId, step, message, null, null, success, screenshot, elementRect, System.currentTimeMillis());
    }

    /** 需要用户输入，prompt 为提示文本（如"请输入验证码"） */
    public static AgentStreamEvent needsInput(String taskId, int step, String prompt) {
        return new AgentStreamEvent("needs_input", taskId, step, prompt, null, null, false, null, null, System.currentTimeMillis());
    }

    /** 任务结束（无截图） */
    public static AgentStreamEvent done(String taskId, boolean success, String message) {
        return new AgentStreamEvent("done", taskId, -1, message, null, null, success, null, null, System.currentTimeMillis());
    }

    /** 任务结束并携带最终页面截图（base64 编码的 PNG） */
    public static AgentStreamEvent doneWithScreenshot(String taskId, boolean success, String message, String screenshot) {
        return new AgentStreamEvent("done", taskId, -1, message, null, null, success, screenshot, null, System.currentTimeMillis());
    }

    /** 发生错误，errorMessage 为错误描述 */
    public static AgentStreamEvent error(String taskId, int step, String errorMessage) {
        return new AgentStreamEvent("error", taskId, step, errorMessage, null, null, false, null, null, System.currentTimeMillis());
    }
}