package com.gao.agent.model;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.openqa.selenium.WebDriver;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 会话模型。
 * 保存一次自动化任务执行过程中的所有运行时状态，支持以下核心能力：
 * <ul>
 *   <li>浏览器驱动（WebDriver）生命周期管理</li>
 *   <li>Agent Loop 暂停/恢复：通过 CountDownLatch 实现线程阻塞等待用户输入</li>
 *   <li>对话历史持久化：暂停后保存完整对话上下文，恢复时继续执行</li>
 *   <li>SSE Emitter 绑定：支持恢复任务时重新绑定新的 SSE 连接</li>
 *   <li>扩展属性：通过 attributes Map 存储自定义键值对</li>
 * </ul>
 *
 * 典型使用流程：
 * <pre>
 * 1. 创建 Session → 启动 Agent Loop 线程
 * 2. Agent 遇到验证码 → 设置 inputPrompt，Session 阻塞等待
 * 3. 用户提交验证码 → provideInput() 唤醒阻塞线程
 * 4. Agent Loop 继续执行 → 完成后关闭 Session
 * </pre>
 */
public class AgentSession {
    /** 任务唯一标识 */
    private String taskId;
    /** Selenium 浏览器驱动实例，用于操作浏览器 */
    private WebDriver driver;
    /** LLM 对话历史，暂停时保存、恢复时继续使用 */
    private List<Map<String, String>> conversationHistory;
    /** 已执行的步骤结果列表 */
    private List<AgentStepResult> steps;
    /** 最后一次采集的页面状态，恢复执行时提供给 LLM 作为上下文 */
    private BrowserState lastState;
    /** 当前已执行的步数（用于恢复后继续计数） */
    private int stepCount;
    /** 等待用户输入时的提示文本（如"请输入验证码"） */
    private String inputPrompt;
    /** 线程同步闩锁：Agent Loop 线程在此阻塞，等待用户输入后释放 */
    private volatile CountDownLatch latch = new CountDownLatch(1);
    /** 用户提交的输入内容（如验证码文本） */
    private volatile String userInput;
    /** Session 创建时间戳（毫秒） */
    private long createdAt = System.currentTimeMillis();
    /** 扩展属性存储，线程安全（ConcurrentHashMap），用于存放 emitter 等附加对象 */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 创建 Agent 会话。
     *
     * @param taskId             任务 ID
     * @param driver             Selenium 浏览器驱动
     * @param conversationHistory 当前对话历史
     * @param steps              已执行的步骤列表
     * @param lastState          最后页面状态
     * @param stepCount          当前步数
     * @param inputPrompt        等待输入提示（首次创建时通常为 null）
     */
    public AgentSession(String taskId, WebDriver driver,
                        List<Map<String, String>> conversationHistory,
                        List<AgentStepResult> steps, BrowserState lastState,
                        int stepCount, String inputPrompt) {
        this.taskId = taskId;
        this.driver = driver;
        this.conversationHistory = conversationHistory;
        this.steps = steps;
        this.lastState = lastState;
        this.stepCount = stepCount;
        this.inputPrompt = inputPrompt;
    }

    /**
     * 阻塞等待用户输入。
     * Agent Loop 线程调用此方法后挂起，直到用户通过 provideInput() 提交内容或超时。
     *
     * @param timeoutMs 最大等待时间（毫秒）
     * @return true=用户已输入，false=超时
     * @throws InterruptedException 线程被中断时抛出
     */
    public boolean waitForInput(long timeoutMs) throws InterruptedException {
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 用户提供输入，唤醒阻塞的 Agent Loop 线程。
     * 由 Controller 层在用户提交验证码等信息时调用。
     *
     * @param input 用户输入的文本
     */
    public synchronized void provideInput(String input) {
        this.userInput = input;
        latch.countDown();
    }

    /**
     * 重置闩锁，为下一次等待用户输入做准备。
     * 每次恢复执行后若再次遇到 needs_input，需调用此方法重新创建 CountDownLatch。
     */
    public synchronized void resetForNextInput() {
        this.latch = new CountDownLatch(1);
        this.userInput = null;
    }

    /**
     * 根据最新的 AgentLoopResult 更新会话状态。
     * 用于恢复执行前同步最新的步骤、对话历史和页面状态。
     *
     * @param result Agent Loop 返回的结果（包含 needsInputPrompt 等信息）
     */
    public void updateForNextInput(AgentLoopResult result) {
        this.inputPrompt = result.getNeedsInputPrompt();
        this.steps = result.getSteps();
        this.stepCount = result.getTotalSteps();
        this.lastState = result.getLastBrowserState();
        this.conversationHistory = result.getConversationHistory();
        resetForNextInput();
    }

    /** 获取扩展属性 */
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /** 设置扩展属性 */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /** 获取绑定的 SSE Emitter（用于向前端推送实时事件） */
    public SseEmitter getEmitter() {
        return (SseEmitter) attributes.get("emitter");
    }

    /** 绑定 SSE Emitter（恢复任务时重新绑定新的连接） */
    public void setEmitter(SseEmitter emitter) {
        attributes.put("emitter", emitter);
    }

    public String getTaskId() { return taskId; }
    public WebDriver getDriver() { return driver; }
    public List<Map<String, String>> getConversationHistory() { return conversationHistory; }
    public List<AgentStepResult> getSteps() { return steps; }
    public BrowserState getLastState() { return lastState; }
    public int getStepCount() { return stepCount; }
    public String getInputPrompt() { return inputPrompt; }
    public String getUserInput() { return userInput; }
    public long getCreatedAt() { return createdAt; }

    /** 关闭浏览器驱动，释放资源。忽略关闭过程中的异常。 */
    public void closeDriver() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }
}
