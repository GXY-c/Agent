package com.gao.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent Loop 执行结果。
 * 封装一次完整 Agent Loop 运行后的所有结果信息，包括：
 * <ul>
 *   <li>执行是否成功、总结描述</li>
 *   <li>每一步的执行详情（steps）</li>
 *   <li>完整对话历史（用于暂停后恢复执行）</li>
 *   <li>最后的页面状态（用于恢复时提供上下文）</li>
 * </ul>
 * 当任务需要用户输入（如验证码）暂停时，needsInputPrompt 不为空，
 * 此时 conversationHistory 和 lastBrowserState 会被保存到 AgentSession 中，
 * 供 resumeFromSession 恢复执行时使用。
 */
public class AgentLoopResult {
    /** 任务是否成功完成 */
    private boolean success;
    /** LLM 返回的任务完成总结（done 动作的 summary 字段） */
    private String summary;
    /** 总执行步数 */
    private int totalSteps;
    /** 每一步的执行详情列表 */
    private List<AgentStepResult> steps = new ArrayList<>();
    /** 面向用户的简短结果消息（如"任务成功，共 7 步"） */
    private String message;
    /** 附加的详细信息（可选） */
    private String details;
    /** 需要用户输入时的提示文本（非空表示任务暂停等待输入） */
    private String needsInputPrompt;
    /** 完整对话历史，用于暂停后恢复 Agent Loop 继续执行 */
    private List<Map<String, String>> conversationHistory;
    /** 最后一次采集的页面状态，用于恢复执行时提供页面上下文 */
    private BrowserState lastBrowserState;

    /** 添加一步执行结果并自动更新总步数 */
    public void addStep(AgentStepResult step) { this.steps.add(step); this.totalSteps = steps.size(); }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }
    public List<AgentStepResult> getSteps() { return steps; }
    public void setSteps(List<AgentStepResult> steps) { this.steps = steps; this.totalSteps = steps.size(); }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getNeedsInputPrompt() { return needsInputPrompt; }
    public void setNeedsInputPrompt(String needsInputPrompt) { this.needsInputPrompt = needsInputPrompt; }
    public List<Map<String, String>> getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<Map<String, String>> conversationHistory) { this.conversationHistory = conversationHistory; }
    public BrowserState getLastBrowserState() { return lastBrowserState; }
    public void setLastBrowserState(BrowserState lastBrowserState) { this.lastBrowserState = lastBrowserState; }
}
