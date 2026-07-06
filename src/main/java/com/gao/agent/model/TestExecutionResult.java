package com.gao.agent.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试执行结果模型。
 * 封装一次完整测试执行（预生成计划模式）的最终结果，
 * 包含整体是否成功、结果消息，以及每一步的执行详情。
 */
public class TestExecutionResult {
    /** 测试整体是否通过 */
    private boolean success;
    /** 结果摘要消息（如"全部 5 步执行成功"） */
    private String message;
    /** 每一步的执行结果列表 */
    private List<TestStepResult> steps = new ArrayList<>();
    /** 附加的详细信息（可选，如失败原因描述） */
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

    /** 添加一步执行结果并返回自身，支持链式调用 */
    public TestExecutionResult addStep(TestStepResult step) {
        this.steps.add(step);
        return this;
    }
}
