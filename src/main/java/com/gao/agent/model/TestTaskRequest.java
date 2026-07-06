package com.gao.agent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

/**
 * 测试任务请求模型。
 * 前端通过 POST /api/automation/tasks 提交任务时传入的请求体。
 * 包含目标 URL、自然语言任务描述、浏览器类型等配置，
 * 后端根据这些信息启动 Agent Loop 自动执行浏览器操作。
 */
public class TestTaskRequest {
    /** 目标页面 URL（必须包含协议前缀，如 http://localhost:5173/login） */
    @NotBlank(message = "targetUrl must not be blank")
    private String targetUrl;

    /** 自然语言任务描述（如"打开登录页，输入用户名 test，密码 123456，点击登录"） */
    @NotBlank(message = "taskDescription must not be blank")
    private String taskDescription;

    /** 浏览器类型，默认使用 Microsoft Edge */
    private BrowserType browser = BrowserType.EDGE;
    /** 是否以可视化模式运行（true=显示浏览器窗口，false=后台无头模式） */
    private boolean visual = true;

    /** 预定义的测试动作列表（可选，为空时由 LLM 根据 taskDescription 自动生成） */
    private List<TestAction> actions = new ArrayList<>();
    /** 附加上下文信息（可选，用于传递额外参数给执行引擎） */
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

    /** 获取浏览器类型，为 null 时默认返回 EDGE */
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
