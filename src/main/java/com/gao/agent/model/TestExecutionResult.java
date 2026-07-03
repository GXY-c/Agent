package com.gao.agent.model;

import java.util.ArrayList;
import java.util.List;

public class TestExecutionResult {
    private boolean success;
    private String message;
    private List<TestStepResult> steps = new ArrayList<>();
    private String details;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<TestStepResult> getSteps() {
        return steps;
    }

    public void setSteps(List<TestStepResult> steps) {
        this.steps = steps;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public TestExecutionResult addStep(TestStepResult step) {
        this.steps.add(step);
        return this;
    }
}
