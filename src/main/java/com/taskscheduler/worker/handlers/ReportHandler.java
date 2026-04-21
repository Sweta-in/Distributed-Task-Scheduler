package com.taskscheduler.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler for generating report tasks.
 * Parses the JSON payload for reportType, dateRange, and format fields,
 * then simulates report generation.
 */
@Component
public class ReportHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportHandler.class);
    private final ObjectMapper objectMapper;

    public ReportHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String handlerName() {
        return "reportHandler";
    }

    @Override
    public void execute(UUID taskId, String jsonPayload) throws Exception {
        MDC.put("taskId", taskId.toString());
        try {
            log.info("ReportHandler executing task: {}", taskId);

            // Parse the JSON payload
            JsonNode payload = objectMapper.readTree(jsonPayload);
            String reportType = payload.has("reportType") ? payload.get("reportType").asText() : "general";
            String dateRange = payload.has("dateRange") ? payload.get("dateRange").asText() : "last_30_days";
            String format = payload.has("format") ? payload.get("format").asText() : "PDF";

            log.info("Generating report: type={}, dateRange={}, format={}", reportType, dateRange, format);

            // Simulate report generation (in production, this would query data and render a report)
            Thread.sleep(1000);

            log.info("Report generated successfully: type={}, format={}, taskId={}", reportType, format, taskId);
        } finally {
            MDC.remove("taskId");
        }
    }
}
