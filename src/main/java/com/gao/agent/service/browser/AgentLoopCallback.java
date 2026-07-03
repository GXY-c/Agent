package com.gao.agent.service.browser;

import com.gao.agent.model.AgentLoopResult;

@FunctionalInterface
public interface AgentLoopCallback {
    void onNeedsInput(AgentLoopResult result);
}