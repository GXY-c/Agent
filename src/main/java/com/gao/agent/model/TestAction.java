package com.gao.agent.model;

import java.util.HashMap;
import java.util.Map;

public class TestAction {
    private TestActionType type;
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

    public TestAction addParameter(String key, Object value) {
        this.parameters.put(key, value);
        return this;
    }
}
