package com.taskscheduler.controller;

import com.taskscheduler.service.QueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom health/status endpoint providing application-specific health details
 * beyond what Spring Actuator provides.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final QueueService queueService;

    public HealthController(QueueService queueService) {
        this.queueService = queueService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> healthInfo = new LinkedHashMap<>();
        healthInfo.put("status", "UP");
        healthInfo.put("timestamp", OffsetDateTime.now());
        healthInfo.put("service", "distributed-task-scheduler");

        // Queue metrics
        Map<String, Object> queueInfo = new LinkedHashMap<>();
        try {
            long queueDepth = queueService.queueSize();
            queueInfo.put("status", "UP");
            queueInfo.put("depth", queueDepth);
        } catch (Exception e) {
            queueInfo.put("status", "DOWN");
            queueInfo.put("error", e.getMessage());
        }
        healthInfo.put("queue", queueInfo);

        return ResponseEntity.ok(healthInfo);
    }
}
