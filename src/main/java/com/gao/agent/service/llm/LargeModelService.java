package com.gao.agent.service.llm;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.ConversationMessage;
import com.gao.agent.model.TestAction;

import java.util.List;
import java.util.Map;

/**
 * 大模型服务接口。
 * 定义了 LLM 在测试自动化中的两种核心能力：
 * <ul>
 *   <li>测试计划生成：根据页面元素生成预定义操作列表（buildTestPlan）</li>
 *   <li>Agent Loop 决策：根据对话历史实时决定下一步动作（decideNextAction）</li>
 * </ul>
 *
 * 同时支持多模态 Vision 模式：
 * <ul>
 *   <li>decideNextActionWithVision — 接收包含截图的对话历史，由 Vision 模型分析页面</li>
 *   <li>supportsVision — 标识实现类是否支持 Vision 多模态</li>
 * </ul>
 *
 * 主要实现类：
 * <ul>
 *   <li>{@code OpenAiLargeModelService} — 调用 OpenAI 兼容 API（如 qwen-max）</li>
 *   <li>{@code MockLargeModelService} — 模拟实现，用于测试</li>
 * </ul>
 */
public interface LargeModelService {

    /**
     * 根据页面元素生成测试计划。
     * 用于预生成计划模式：一次性生成完整的操作列表，再由浏览器逐步执行。
     *
     * @param targetUrl      目标页面 URL
     * @param taskDescription 自然语言任务描述
     * @param pageElements   页面上可交互元素的文本描述
     * @return 预定义的测试动作列表
     */
    List<TestAction> buildTestPlan(String targetUrl, String taskDescription, String pageElements);

    /**
     * Agent Loop 决策：根据对话历史决定下一步动作。
     * 对话历史包含系统提示、页面状态反馈、之前的操作记录等，
     * LLM 从中分析当前页面状态，返回下一个动作（click/input/done 等）。
     *
     * @param conversationHistory 对话历史（role + content 格式的文本消息列表）
     * @return LLM 决策的下一个动作
     */
    AgentAction decideNextAction(List<Map<String, String>> conversationHistory);

    /**
     * 多模态 Agent Loop 决策：支持截图 + 文本的对话历史。
     * 默认实现将 ConversationMessage 降级为纯文本格式，委托给 decideNextAction。
     * 支持 Vision 的实现类（如 OpenAiLargeModelService）可覆写此方法，
     * 将截图以 base64 图片形式发送给 Vision API。
     *
     * @param messages 包含文本和截图的对话历史
     * @return LLM 决策的下一个动作
     */
    default AgentAction decideNextActionWithVision(List<ConversationMessage> messages) {
        return decideNextAction(toLegacyFormat(messages));
    }

    /** 标识当前实现是否支持 Vision 多模态（截图分析） */
    default boolean supportsVision() {
        return false;
    }

    /** 将 ConversationMessage 列表降级为纯文本 Map 格式（丢弃截图数据） */
    private static List<Map<String, String>> toLegacyFormat(List<ConversationMessage> messages) {
        return messages.stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getTextContent() != null ? m.getTextContent() : ""))
                .toList();
    }
}
