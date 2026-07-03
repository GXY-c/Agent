package com.gao.agent.model;

public class AgentStepResult {
    private int stepNumber;
    private AgentAction action;
    private boolean success;
    private String message;
    private String screenshot;      // base64
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
