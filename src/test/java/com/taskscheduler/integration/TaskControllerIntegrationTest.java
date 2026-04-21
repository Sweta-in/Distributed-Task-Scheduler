package com.taskscheduler.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskscheduler.dto.TaskCreateRequest;
import com.taskscheduler.dto.TaskResponse;
import com.taskscheduler.model.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TaskControllerIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("taskscheduler_test")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TaskCreateRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new TaskCreateRequest();
        validRequest.setName("Integration Test Email");
        validRequest.setPayload("{\"recipient\":\"test@example.com\",\"subject\":\"Hello\",\"body\":\"World\"}");
        validRequest.setHandler("emailHandler");
        validRequest.setPriority(TaskPriority.HIGH.getValue());
        validRequest.setMaxRetries(3);
    }

    @Test
    @DisplayName("POST /api/tasks should return 201 with task response")
    void submitTask_returns201WithBody() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Integration Test Email"))
                .andExpect(jsonPath("$.handler").value("emailHandler"))
                .andExpect(jsonPath("$.priority").value(TaskPriority.HIGH.getValue()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.maxRetries").value(3))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/tasks/{id} should return task matching POST response")
    void getTask_matchesSubmittedTask() throws Exception {
        // Submit a task
        MvcResult createResult = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TaskResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), TaskResponse.class);

        // GET by ID
        mockMvc.perform(get("/api/tasks/{id}", created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.name").value("Integration Test Email"))
                .andExpect(jsonPath("$.handler").value("emailHandler"))
                .andExpect(jsonPath("$.priority").value(TaskPriority.HIGH.getValue()));
    }

    @Test
    @DisplayName("GET /api/tasks/{id} should return 404 for non-existent task")
    void getTask_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("not found")));
    }

    @Test
    @DisplayName("GET /api/tasks should return paginated list")
    void listTasks_returnsPaginatedList() throws Exception {
        // Submit a few tasks
        for (int i = 0; i < 3; i++) {
            TaskCreateRequest req = new TaskCreateRequest();
            req.setName("List Test Task " + i);
            req.setPayload("{}");
            req.setHandler("emailHandler");
            req.setPriority(5);
            req.setMaxRetries(3);

            mockMvc.perform(post("/api/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)));
        }

        // List all tasks
        mockMvc.perform(get("/api/tasks")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)));
    }

    @Test
    @DisplayName("GET /api/tasks?status=PENDING should filter by status")
    void listTasks_withStatusFilter() throws Exception {
        // Submit a task
        mockMvc.perform(post("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validRequest)));

        // List PENDING tasks
        mockMvc.perform(get("/api/tasks")
                        .param("status", "PENDING")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].status", everyItem(is("PENDING"))));
    }

    @Test
    @DisplayName("POST /api/tasks with invalid request should return 400")
    void submitTask_invalidRequest_returns400() throws Exception {
        TaskCreateRequest invalid = new TaskCreateRequest();
        // Missing required fields: name and handler

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    @DisplayName("DELETE /api/tasks/{id} should cancel PENDING task and return 204")
    void cancelTask_pending_returns204() throws Exception {
        // Submit a task first
        MvcResult createResult = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        TaskResponse created = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), TaskResponse.class);

        // Cancel the task
        mockMvc.perform(delete("/api/tasks/{id}", created.getId()))
                .andExpect(status().isNoContent());
    }
}
