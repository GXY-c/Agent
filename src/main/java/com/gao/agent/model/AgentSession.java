package com.gao.agent.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.openqa.selenium.WebDriver;

public class AgentSession {
    private String taskId;
    private WebDriver driver;
    private List<Map<String, String>> conversationHistory;
    private List<AgentStepResult> steps;
    private BrowserState lastState;
    private int stepCount;
    private String inputPrompt;
    private volatile CountDownLatch latch = new CountDownLatch(1);
    private volatile String userInput;
    private long createdAt = System.currentTimeMillis();

    public AgentSession(String taskId, WebDriver driver,
                        List<Map<String, String>> conversationHistory,
                        List<AgentStepResult> steps, BrowserState lastState,
                        int stepCount, String inputPrompt) {
        this.taskId = taskId;
        this.driver = driver;
        this.conversationHistory = conversationHistory;
        this.steps = steps;
        this.lastState = lastState;
        this.stepCount = stepCount;
        this.inputPrompt = inputPrompt;
    }

    public boolean waitForInput(long timeoutMs) throws InterruptedException {
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void provideInput(String input) {
        this.userInput = input;
        latch.countDown();
    }

    public synchronized void resetForNextInput() {
        this.latch = new CountDownLatch(1);
        this.userInput = null;
    }

    public void updateForNextInput(AgentLoopResult result) {
        this.inputPrompt = result.getNeedsInputPrompt();
        this.steps = result.getSteps();
        this.stepCount = result.getTotalSteps();
        this.lastState = result.getLastBrowserState();
        this.conversationHistory = result.getConversationHistory();
        resetForNextInput();
    }

    public String getTaskId() { return taskId; }
    public WebDriver getDriver() { return driver; }
    public List<Map<String, String>> getConversationHistory() { return conversationHistory; }
    public List<AgentStepResult> getSteps() { return steps; }
    public BrowserState getLastState() { return lastState; }
    public int getStepCount() { return stepCount; }
    public String getInputPrompt() { return inputPrompt; }
    public String getUserInput() { return userInput; }
    public long getCreatedAt() { return createdAt; }

    public void closeDriver() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }
}
