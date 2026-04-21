package com.taskscheduler.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handler for sending email tasks.
 * Parses the JSON payload for recipient, subject, and body fields,
 * then simulates sending an email.
 */
@Component
public class EmailHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailHandler.class);
    private final ObjectMapper objectMapper;

    public EmailHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String handlerName() {
        return "emailHandler";
    }

    @Override
    public void execute(UUID taskId, String jsonPayload) throws Exception {
        MDC.put("taskId", taskId.toString());
        try {
            log.info("EmailHandler executing task: {}", taskId);

            // Parse the JSON payload
            JsonNode payload = objectMapper.readTree(jsonPayload);
            String recipient = payload.has("recipient") ? payload.get("recipient").asText() : "unknown";
            String subject = payload.has("subject") ? payload.get("subject").asText() : "(no subject)";
            String body = payload.has("body") ? payload.get("body").asText() : "";

            log.info("Sending email to={}, subject='{}', bodyLength={}", recipient, subject, body.length());

            // Simulate email sending (in production, this would integrate with SMTP/SendGrid/SES)
            Thread.sleep(500);

            log.info("Email sent successfully to {} for task {}", recipient, taskId);
        } finally {
            MDC.remove("taskId");
        }
    }
}
