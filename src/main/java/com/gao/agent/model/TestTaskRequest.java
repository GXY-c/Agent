package com.gao.agent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public class TestTaskRequest {

    @NotBlank(message = "targetUrl must not be blank")
    private String targetUrl;

    @NotBlank(message = "taskDescription must not be blank")
    private String taskDescription;

    private BrowserType browser = BrowserType.EDGE;
    private boolean visual = true;

    private List<TestAction> actions = new ArrayList<>();
    private Map<String, Object> context = new HashMap<>();

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public void setTaskDescription(String taskDescription) {
        this.taskDescription = taskDescription;
    }

    public BrowserType getBrowser() {
        return browser == null ? BrowserType.EDGE : browser;
    }

    public void setBrowser(BrowserType browser) {
        this.browser = browser;
    }

    public boolean isVisual() {
        return visual;
    }

    public void setVisual(boolean visual) {
        this.visual = visual;
    }

    public List<TestAction> getActions() {
        return actions;
    }

    public void setActions(List<TestAction> actions) {
        this.actions = actions;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }
}
