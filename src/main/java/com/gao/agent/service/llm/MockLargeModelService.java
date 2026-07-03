package com.gao.agent.service.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.gao.agent.model.AgentAction;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestActionType;

@Component
public class MockLargeModelService implements LargeModelService {

    @Override
    public List<TestAction> buildTestPlan(String targetUrl, String taskDescription, String pageElements) {
        List<TestAction> actions = new ArrayList<>();
        actions.add(createNavigateAction(targetUrl));

        if (taskDescription.contains("登录") || taskDescription.contains("用户名") || taskDescription.contains("密码")) {
            actions.add(createTypeAction("input[type=text]:first-of-type", "liujiajan"));
            actions.add(createTypeAction("input[type=password]", "123456"));
            actions.add(createTypeAction("input[placeholder*=验证码],input[name*=code],input[name*=captcha],input[type=text]:nth-of-type(3)", "Y1ZY"));
            actions.add(createClickAction("button[type=submit],input[type=submit],button"));
        } else {
            if (taskDescription.contains("点击")) {
                actions.add(createClickAction("button"));
            }
            if (taskDescription.contains("输入")) {
                actions.add(createTypeAction("input", "测试内容"));
            }
            if (taskDescription.contains("检查") || taskDescription.contains("断言")) {
                actions.add(createAssertTextAction("body", "测试"));
            }
        }

        if (actions.size() == 1) {
            actions.add(createWaitAction(1));
        }

        return actions;
    }

    private TestAction createNavigateAction(String url) {
        TestAction action = new TestAction(TestActionType.NAVIGATE);
        action.addParameter("url", url);
        return action;
    }

    private TestAction createClickAction(String selector) {
        TestAction action = new TestAction(TestActionType.CLICK);
        action.addParameter("selector", selector);
        return action;
    }

    private TestAction createTypeAction(String selector, String text) {
        TestAction action = new TestAction(TestActionType.TYPE);
        action.addParameter("selector", selector);
        action.addParameter("text", text);
        return action;
    }

    private TestAction createAssertTextAction(String selector, String expectedText) {
        TestAction action = new TestAction(TestActionType.ASSERT_TEXT);
        action.addParameter("selector", selector);
        action.addParameter("text", expectedText);
        return action;
    }

    private TestAction createWaitAction(int seconds) {
        TestAction action = new TestAction(TestActionType.WAIT);
        action.addParameter("seconds", seconds);
        return action;
    }

    @Override
    public AgentAction decideNextAction(List<Map<String, String>> conversationHistory) {
        AgentAction action = new AgentAction();
        action.setAction(AgentAction.ActionType.done);
        action.setSummary("Mock: 任务完成");
        action.setThinking("Mock 模式，直接返回 done");
        return action;
    }
}
