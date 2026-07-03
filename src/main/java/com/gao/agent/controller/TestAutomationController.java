package com.gao.agent.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gao.agent.model.TaskSubmissionResponse;
import com.gao.agent.model.TestTaskRequest;
import com.gao.agent.model.TestTaskResponse;
import com.gao.agent.service.TestAutomationService;

import jakarta.validation.Valid;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/automation")
public class TestAutomationController {

    private final TestAutomationService automationService;

    public TestAutomationController(TestAutomationService automationService) {
        this.automationService = automationService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskSubmissionResponse> submitTask(@Valid @RequestBody TestTaskRequest request) {
        TaskSubmissionResponse response = automationService.submitTask(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<TestTaskResponse> getTaskStatus(@PathVariable String taskId) {
        TestTaskResponse taskResponse = automationService.getTask(taskId);
        if (taskResponse == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskResponse);
    }

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

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<?> cancelTask(@PathVariable String taskId) {
        TestTaskResponse taskResponse = automationService.cancelTask(taskId);
        if (taskResponse == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(taskResponse);
    }
}
