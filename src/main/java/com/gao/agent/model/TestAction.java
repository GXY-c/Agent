package com.gao.agent.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试动作模型。
 * 由 LLM 在测试计划生成阶段（buildTestPlan）返回，描述一个浏览器自动化操作。
 * 每个动作包含类型（TestActionType）和一组键值对参数，
 * 例如 NAVIGATE 动作携带 url 参数，TYPE 动作携带 index + text 参数。
 *
 * 与 AgentAction 的区别：
 * <ul>
 *   <li>TestAction — 用于预生成测试计划模式（一次性生成全部步骤）</li>
 *   <li>AgentAction — 用于 Agent Loop 模式（逐步决策）</li>
 * </ul>
 */
public class TestAction {
    /** 动作类型（NAVIGATE / CLICK / TYPE / ASSERT_TEXT / WAIT / EXECUTE_JS） */
    private TestActionType type;
    /** 动作参数（键值对），不同动作类型使用不同的参数组合 */
    private Map<String, Object> parameters = new HashMap<>();

    public TestAction() {
    }

    public TestAction(TestActionType type) {
        this.type = type;
    }

    public TestActionType getType() {
        return type;
    }

    public void setType(TestActionType type) {
        this.type = type;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    /**
     * 添加一个参数并返回自身，支持链式调用。
     * 示例：new TestAction(TestActionType.TYPE).addParameter("index", 0).addParameter("text", "admin")
     */
    public TestAction addParameter(String key, Object value) {
        this.parameters.put(key, value);
        return this;
    }
}
