package com.gao.agent.service.browser;

import com.gao.agent.model.AgentLoopResult;

/**
 * Agent Loop 回调接口（函数式接口）。
 * 当 Agent Loop 遇到需要用户输入的场景（如验证码）而暂停时，
 * 通过此回调通知上层调用者（如 TestAutomationServiceImpl），
 * 以便创建 AgentSession 并等待用户提交输入后恢复执行。
 */
@FunctionalInterface
public interface AgentLoopCallback {
    /**
     * Agent Loop 暂停时的回调方法。
     *
     * @param result Agent Loop 结果，包含 needsInputPrompt（等待输入提示）、
     *               conversationHistory（对话历史）、lastBrowserState（页面状态）等，
     *               用于后续恢复执行
     */
    void onNeedsInput(AgentLoopResult result);
}