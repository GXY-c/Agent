package com.gao.agent.model;

/**
 * 测试步骤执行结果模型（预生成计划模式）。
 * 记录单个测试步骤的执行情况：操作描述、是否成功、截图和详细信息。
 * 多个 TestStepResult 组成 TestExecutionResult 的 steps 列表。
 */
public class TestStepResult {
    /** 步骤操作描述（如"点击登录按钮"） */
    private String description;
    /** 该步骤是否执行成功 */
    private boolean success;
    /** 执行后的页面截图，base64 编码的 PNG 数据 */
    private String screenshotBase64;
    /** 附加详细信息（如失败原因、元素状态等） */
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
