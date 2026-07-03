package com.gao.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AgentLoopResult {
    private boolean success;
    private String summary;
    private int totalSteps;
    private List<AgentStepResult> steps = new ArrayList<>();
    private String message;
    private String details;
    private String needsInputPrompt;
    private List<Map<String, String>> conversationHistory;
    private BrowserState lastBrowserState;

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
