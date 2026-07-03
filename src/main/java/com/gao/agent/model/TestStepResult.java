package com.gao.agent.model;

public class TestStepResult {
    private String description;
    private boolean success;
    private String screenshotBase64;
    private String details;

    public TestStepResult() {
    }

    public TestStepResult(String description, boolean success, String screenshotBase64, String details) {
        this.description = description;
        this.success = success;
        this.screenshotBase64 = screenshotBase64;
        this.details = details;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getScreenshotBase64() {
        return screenshotBase64;
    }

    public void setScreenshotBase64(String screenshotBase64) {
        this.screenshotBase64 = screenshotBase64;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
