package com.gao.agent.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.gao.agent.model.TaskSubmissionResponse;
import com.gao.agent.model.TestTaskRequest;
import com.gao.agent.model.TestTaskResponse;
import com.gao.agent.service.TestAutomationService;

import jakarta.validation.Valid;

/**
 * 自动化测试任务控制器。
 * 提供任务提交、SSE 实时进度推送、任务状态查询、恢复执行和取消任务等 REST API。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/automation")
public class TestAutomationController {

    private final TestAutomationService automationService;

    public TestAutomationController(TestAutomationService automationService) {
        this.automationService = automationService;
    }

    /**
     * 提交新的自动化测试任务。
     * 前端传入目标 URL、任务描述、浏览器类型等配置，后端异步启动执行。
     *
     * @param request 任务请求（包含 targetUrl、taskDescription、browser、visual 等）
     * @return 包含 taskId 的提交响应
     */
    @PostMapping("/tasks")
    public ResponseEntity<TaskSubmissionResponse> submitTask(@Valid @RequestBody TestTaskRequest request) {
        TaskSubmissionResponse response = automationService.submitTask(request);
        return ResponseEntity.ok(response);
    }

    /**
     * SSE 实时推送任务执行进度。
     * 前端通过 EventSource 连接此端点，接收 thinking、action_start、action_complete、
     * needs_input、done、error 等事件类型，实现执行过程的实时可视化。
     * 超时时间设为 5 分钟（300,000ms）。
     *
     * @param taskId 任务 ID
     * @return SseEmitter 事件流
     */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTaskProgress(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        automationService.registerEmitter(taskId, emitter);
        return emitter;
    }

    /**
     * 查询任务当前状态。
     * 返回任务的状态（PENDING/RUNNING/SUCCESS/FAILED/WAITING_INPUT）、结果摘要等信息。
     *
     * @param taskId 任务 ID
     * @return 任务状态响应，不存在时返回 404
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TestTaskResponse> getTaskStatus(@PathVariable String taskId) {
        TestTaskResponse taskResponse = automationService.getTask(taskId);
        if (taskResponse == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskResponse);
    }

    /**
     * 恢复等待输入状态的任务。
     * 当 Agent 遇到验证码等需要人工干预的场景时，任务会暂停并等待用户输入。
     * 此接口接收用户提供的信息（如验证码文本），恢复 Agent Loop 继续执行。
     *
     * @param taskId 任务 ID
     * @param body   请求体，包含 "input" 字段（用户输入内容）
     * @return 更新后的任务状态，任务不在等待状态或缺少输入时返回 400
     */
    @PostMapping("/tasks/{taskId}/resume")
    public ResponseEntity<?> resumeTask(@PathVariable String taskId, @RequestBody Map<String, String> body) {
        TestTaskResponse taskResponse = automationService.getTask(taskId);
        if (taskResponse == null) {
            return ResponseEntity.notFound().build();
        }
        if (taskResponse.getStatus() != com.gao.agent.model.TestTaskStatus.WAITING_INPUT) {
            return ResponseEntity.badRequest().body(Map.of("error", "任务当前不在等待输入状态"));
        }
        String userInput = body.get("input");
        if (userInput == null || userInput.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请提供输入内容"));
        }
        TestTaskResponse updated = automationService.resumeTask(taskId, userInput);
        return ResponseEntity.ok(updated);
    }

    /**
     * 取消正在执行的任务。
     * 中断 Agent Loop 并关闭浏览器会话。
     *
     * @param taskId 任务 ID
     * @return 取消后的任务状态，不存在时返回 404
     */
    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        TestTaskResponse taskResponse = automationService.cancelTask(taskId);
        if (taskResponse == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskResponse);
    }
}
