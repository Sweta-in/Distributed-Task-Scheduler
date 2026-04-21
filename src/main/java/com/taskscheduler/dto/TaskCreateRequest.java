package com.taskscheduler.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

public class TaskCreateRequest {

    @NotBlank(message = "Task name is required")
    private String name;

    private String payload;

    @NotBlank(message = "Handler name is required")
    private String handler;

    @Min(value = 1, message = "Priority must be between 1 (HIGH) and 10 (LOW)")
    @Max(value = 10, message = "Priority must be between 1 (HIGH) and 10 (LOW)")
    private int priority = 5;

    @Min(value = 0, message = "Max retries must be non-negative")
    @Max(value = 10, message = "Max retries cannot exceed 10")
    private int maxRetries = 3;

    private OffsetDateTime scheduledAt;

    public TaskCreateRequest() {
    }

    public TaskCreateRequest(String name, String payload, String handler, int priority, int maxRetries, OffsetDateTime scheduledAt) {
        this.name = name;
        this.payload = payload;
        this.handler = handler;
        this.priority = priority;
        this.maxRetries = maxRetries;
        this.scheduledAt = scheduledAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
