package com.gao.agent.service.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestActionType;

/**
 * 大模型服务的 Mock 实现。
 * 不调用真实 LLM API，而是通过硬编码规则模拟 LLM 的决策行为，
 * 用于开发调试和单元测试，无需消耗 API 额度即可验证整体流程。
 *
 * 模拟策略：
 * <ul>
 *   <li>buildTestPlan：根据任务描述关键词（登录/点击/输入/检查）生成固定动作序列</li>
 *   <li>decideNextAction：通过 stepCounter 按步骤顺序返回预设动作，
 *       最多执行 5 步后返回 done</li>
 * </ul>
 *
 * 注意：此实现不支持 Vision 多模态。
 */
@Component
public class MockLargeModelService implements LargeModelService {

    private static final Logger log = LoggerFactory.getLogger(MockLargeModelService.class);

    /** 模拟登录用的用户名 */
    private static final String MOCK_USERNAME = "admin";
    /** 模拟登录用的密码 */
    private static final String MOCK_PASSWORD = "123456";
    /** 模拟登录用的验证码 */
    private static final String MOCK_CAPTCHA = "ABCD";

    /** 步骤计数器，用于按顺序返回预设动作 */
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    /**
     * 模拟生成测试计划。
     * 根据任务描述中的关键词生成固定动作序列：
     * <ul>
     *   <li>登录任务 → 输入用户名/密码/验证码 + 点击登录按钮</li>
     *   <li>包含"点击" → 生成 CLICK 动作</li>
     *   <li>包含"输入" → 生成 TYPE 动作</li>
     *   <li>包含"检查/断言" → 生成 ASSERT_TEXT 动作</li>
     * </ul>
     */
    @Override
    public List<TestAction> buildTestPlan(String targetUrl, String taskDescription, String pageElements) {
        log.info("[Mock] buildTestPlan: url={}, task={}, pageElements={}",
                targetUrl, taskDescription, pageElements != null ? pageElements.length() + " chars" : "null");

        List<TestAction> actions = new ArrayList<>();
        actions.add(new TestAction(TestActionType.NAVIGATE).addParameter("url", targetUrl));
        actions.add(new TestAction(TestActionType.WAIT).addParameter("ms", 2000));

        if (isLoginTask(taskDescription)) {
            actions.add(new TestAction(TestActionType.TYPE).addParameter("index", 0).addParameter("text", MOCK_USERNAME));
            actions.add(new TestAction(TestActionType.TYPE).addParameter("index", 1).addParameter("text", MOCK_PASSWORD));
            actions.add(new TestAction(TestActionType.TYPE).addParameter("index", 2).addParameter("text", MOCK_CAPTCHA));
            actions.add(new TestAction(TestActionType.CLICK).addParameter("index", 3));
        } else {
            if (taskDescription.contains("点击")) {
                actions.add(new TestAction(TestActionType.CLICK).addParameter("index", 0));
            }
            if (taskDescription.contains("输入")) {
                actions.add(new TestAction(TestActionType.TYPE).addParameter("index", 0).addParameter("text", "测试内容"));
            }
            if (taskDescription.contains("检查") || taskDescription.contains("断言")) {
                actions.add(new TestAction(TestActionType.ASSERT_TEXT).addParameter("index", 0).addParameter("text", "测试"));
            }
        }

        if (actions.size() <= 2) {
            actions.add(new TestAction(TestActionType.WAIT).addParameter("ms", 1000));
        }

        log.info("[Mock] Generated {} actions", actions.size());
        return actions;
    }

    /**
     * 模拟 Agent Loop 决策。
     * 根据 stepCounter 和对话历史内容返回预设动作：
     * <ul>
     *   <li>第 0 步 → go_to_url 导航</li>
     *   <li>第 1 步 → wait 等待</li>
     *   <li>后续步骤 → 根据页面状态关键词返回 input_text/click_element</li>
     *   <li>第 5 步 → 返回 done 结束任务</li>
     * </ul>
     */
    @Override
    public AgentAction decideNextAction(List<Map<String, String>> conversationHistory) {
        int step = stepCounter.getAndIncrement();
        log.info("[Mock] decideNextAction: step={}, historySize={}", step, conversationHistory.size());

        String lastUserContent = extractLastUserContent(conversationHistory);

        if (lastUserContent != null && lastUserContent.contains("页面状态")) {
            if (step >= 5) {
                log.info("[Mock] Returning done after {} steps", step);
                stepCounter.set(0);
                return buildDoneAction("Mock: 任务已完成", true);
            }
            if (lastUserContent.contains("登录") || lastUserContent.contains("用户名")) {
                return buildAction(AgentAction.ActionType.input_text, 0, "admin");
            }
            if (lastUserContent.contains("密码")) {
                return buildAction(AgentAction.ActionType.input_text, 1, "123456");
            }
            return buildAction(AgentAction.ActionType.click_element, 2, null);
        }

        if (step == 0) {
            AgentAction action = new AgentAction();
            action.setAction(AgentAction.ActionType.go_to_url);
            action.setUrl("http://localhost:8080");
            action.setThinking("Mock: 导航到目标页面");
            log.info("[Mock] Step 0: go_to_url");
            return action;
        }

        if (step == 1) {
            return buildAction(AgentAction.ActionType.wait, null, null);
        }

        if (step >= 4) {
            stepCounter.set(0);
            return buildDoneAction("Mock: 模拟任务完成", true);
        }

        return buildAction(AgentAction.ActionType.click_element, step, null);
    }

    /** 判断是否为登录任务（任务描述中包含"登录/用户名/密码"关键词） */
    private boolean isLoginTask(String taskDescription) {
        return taskDescription.contains("登录") || taskDescription.contains("用户名") || taskDescription.contains("密码");
    }

    /** 从对话历史中提取最后一条 user 消息的内容 */
    private String extractLastUserContent(List<Map<String, String>> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return null;
        }
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            Map<String, String> msg = conversationHistory.get(i);
            if ("user".equals(msg.get("role"))) {
                return msg.get("content");
            }
        }
        return null;
    }

    /** 构建指定类型的 AgentAction */
    private AgentAction buildAction(AgentAction.ActionType type, Integer index, String text) {
        AgentAction action = new AgentAction();
        action.setAction(type);
        action.setIndex(index);
        action.setText(text);
        action.setThinking("Mock: " + type + (index != null ? " index=" + index : ""));
        log.debug("[Mock] Action: type={}, index={}, text={}", type, index, text);
        return action;
    }

    /** 构建 done 动作（结束任务） */
    private AgentAction buildDoneAction(String summary, boolean success) {
        AgentAction action = new AgentAction();
        action.setAction(AgentAction.ActionType.done);
        action.setSuccess(success);
        action.setSummary(summary);
        action.setThinking("Mock: 任务结束");
        log.info("[Mock] Done: success={}, summary={}", success, summary);
        return action;
    }
}
