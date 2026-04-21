package com.taskscheduler.controller;

import com.taskscheduler.dto.TaskCreateRequest;
import com.taskscheduler.dto.TaskResponse;
import com.taskscheduler.model.TaskStatus;
import com.taskscheduler.service.TaskService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * POST /api/tasks — Submit a new task.
     * Persists to DB, enqueues to Redis, returns 201.
     */
    @PostMapping
    public ResponseEntity<TaskResponse> submitTask(@Valid @RequestBody TaskCreateRequest request) {
        log.info("Received task submission: name={}, handler={}, priority={}",
                request.getName(), request.getHandler(), request.getPriority());
        TaskResponse response = taskService.submitTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/tasks/{id} — Get task by UUID.
     * Returns 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable UUID id) {
        TaskResponse response = taskService.getTask(id);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/tasks?status=PENDING&page=0&size=50 — Paginated list with optional status filter.
     */
    @GetMapping
    public ResponseEntity<Page<TaskResponse>> listTasks(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<TaskResponse> tasks = taskService.listTasks(status, page, size);
        return ResponseEntity.ok(tasks);
    }

    /**
     * DELETE /api/tasks/{id} — Cancel a task (only if PENDING).
     * Returns 204 on success.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelTask(@PathVariable UUID id) {
        log.info("Received cancel request for task: {}", id);
        taskService.cancelTask(id);
        return ResponseEntity.noContent().build();
    }
}
