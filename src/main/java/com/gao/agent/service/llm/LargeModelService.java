package com.gao.agent.service.llm;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.TestAction;

import java.util.List;
import java.util.Map;

public interface LargeModelService {
    List<TestAction> buildTestPlan(String targetUrl, String taskDescription, String pageElements);

    /**
     * Agent Loop 模式：根据历史对话决定下一步动作。
     * @param conversationHistory 历史消息列表，每项是 {"role": "system/user/assistant", "content": "..."}
     * @return 下一步动作
     */
    AgentAction decideNextAction(List<Map<String, String>> conversationHistory);
}
