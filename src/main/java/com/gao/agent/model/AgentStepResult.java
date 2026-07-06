package com.gao.agent.model;

/**
 * Agent 单步执行结果。
 * 记录每一步的完整执行信息：执行了什么动作、是否成功、执行前的页面状态和截图。
 * 多个 AgentStepResult 组成 steps 列表，保存在 AgentLoopResult 中，
 * 用于任务完成后的结果展示和调试回溯。
 */
public class AgentStepResult {
    /** 步骤编号（从 1 开始） */
    private int stepNumber;
    /** 本步执行的动作（包含 action 类型、index、text 等 LLM 决策信息） */
    private AgentAction action;
    /** 本步是否执行成功 */
    private boolean success;
    /** 执行结果描述（如"✅ 成功"或"❌ 失败 - 元素不存在"） */
    private String message;
    /** 执行前的页面截图，base64 编码的 PNG 数据（data:image/png;base64,...） */
    private String screenshot;
    /** 执行动作前的页面状态（元素列表、URL 等），用于调试和结果回溯 */
    private BrowserState stateBefore;

    public int getStepNumber() { return stepNumber; }
    public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }
    public AgentAction getAction() { return action; }
    public void setAction(AgentAction action) { this.action = action; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getScreenshot() { return screenshot; }
    public void setScreenshot(String screenshot) { this.screenshot = screenshot; }
    public BrowserState getStateBefore() { return stateBefore; }
    public void setStateBefore(BrowserState stateBefore) { this.stateBefore = stateBefore; }
}
