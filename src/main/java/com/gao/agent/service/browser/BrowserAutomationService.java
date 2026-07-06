package com.gao.agent.service.browser;

import java.util.List;

import com.gao.agent.model.AgentLoopResult;
import com.gao.agent.model.AgentSession;
import com.gao.agent.model.TestAction;
import com.gao.agent.model.TestExecutionResult;

/**
 * 浏览器自动化服务接口。
 * 提供浏览器自动化执行和 Session 管理能力：
 * <ul>
 *   <li>executeSteps — 执行预定义的测试步骤列表</li>
 *   <li>runAgentLoop — Agent Loop 模式，逐步由 LLM 实时决策执行</li>
 *   <li>getSession / closeSession — Session 管理，支持暂停/恢复</li>
 * </ul>
 *
 * 主要实现类：{@code SeleniumBrowserAutomationService}
 */
public interface BrowserAutomationService {

    /**
     * 执行预定义的测试步骤列表。
     *
     * @param targetUrl    目标页面 URL
     * @param steps        预定义的测试动作列表
     * @param browserName  浏览器类型（EDGE / CHROME）
     * @param visual       是否可视化模式（显示浏览器窗口）
     * @return 测试执行结果
     */
    TestExecutionResult executeSteps(String targetUrl, List<TestAction> steps, String browserName, boolean visual);

    /**
     * 启动 Agent Loop 模式执行任务。
     *
     * @param targetUrl      目标页面 URL
     * @param taskDescription 自然语言任务描述
     * @param browserName    浏览器类型
     * @param visual         是否可视化模式
     * @return Agent Loop 执行结果
     */
    AgentLoopResult runAgentLoop(String targetUrl, String taskDescription, String browserName, boolean visual);
    
    /** 关闭指定任务的 Session，释放浏览器驱动资源 */
    default void closeSession(String taskId) {}
    
    /** 获取指定任务的 Session（包含对话历史、页面状态等，用于恢复执行） */
    default AgentSession getSession(String taskId) { return null; }
}
