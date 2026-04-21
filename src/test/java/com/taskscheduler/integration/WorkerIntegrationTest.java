package com.taskscheduler.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskscheduler.dto.TaskCreateRequest;
import com.taskscheduler.dto.TaskResponse;
import com.taskscheduler.model.TaskPriority;
import com.taskscheduler.model.TaskStatus;
import com.taskscheduler.repository.TaskRepository;
import com.taskscheduler.worker.handlers.TaskHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class WorkerIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("taskscheduler_worker_test")
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

    @Autowired
    private TaskRepository taskRepository;

    // ---- Test handlers registered via TestConfiguration ----

    static final CountDownLatch successLatch = new CountDownLatch(1);
    static final AtomicBoolean successHandlerInvoked = new AtomicBoolean(false);
    static final AtomicInteger failHandlerInvocations = new AtomicInteger(0);

    @TestConfiguration
    static class TestHandlerConfig {

        @Bean
        public TaskHandler testSuccessHandler() {
            return new TaskHandler() {
                @Override
                public String handlerName() {
                    return "testSuccessHandler";
                }

                @Override
                public void execute(UUID taskId, String jsonPayload) {
                    successHandlerInvoked.set(true);
                    successLatch.countDown();
                }
            };
        }

        @Bean
        public TaskHandler testFailHandler() {
            return new TaskHandler() {
                @Override
                public String handlerName() {
                    return "testFailHandler";
                }

                @Override
                public void execute(UUID taskId, String jsonPayload) throws Exception {
                    failHandlerInvocations.incrementAndGet();
                    throw new RuntimeException("Intentional test failure");
                }
            };
        }
    }

    @Test
    @DisplayName("Submit task with success handler → worker picks up → status becomes COMPLETED")
    void workerProcessesTask_successfully() throws Exception {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("Worker Integration Success Test");
        request.setPayload("{\"test\": true}");
        request.setHandler("testSuccessHandler");
        request.setPriority(TaskPriority.HIGH.getValue());
        request.setMaxRetries(3);

        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TaskResponse created = objectMapper.readValue(
                result.getResponse().getContentAsString(), TaskResponse.class);

        // Wait for the worker to process the task
        boolean processed = successLatch.await(30, TimeUnit.SECONDS);
        assertTrue(processed, "Task was not processed within timeout");
        assertTrue(successHandlerInvoked.get(), "Success handler was not invoked");

        // Wait for DB status to be COMPLETED
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    var task = taskRepository.findById(created.getId());
                    return task.isPresent() && task.get().getStatus() == TaskStatus.COMPLETED;
                });

        var completedTask = taskRepository.findById(created.getId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, completedTask.getStatus());
        assertNotNull(completedTask.getCompletedAt());
    }

    @Test
    @DisplayName("Submit task with failing handler → status becomes RETRYING then FAILED after max retries")
    void workerProcessesTask_failsAndRetries() throws Exception {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setName("Worker Integration Fail Test");
        request.setPayload("{\"test\": true}");
        request.setHandler("testFailHandler");
        request.setPriority(TaskPriority.HIGH.getValue());
        request.setMaxRetries(1); // Only 1 retry allowed

        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        TaskResponse created = objectMapper.readValue(
                result.getResponse().getContentAsString(), TaskResponse.class);

        // Wait for the first execution (should fail and enter RETRYING)
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    var task = taskRepository.findById(created.getId());
                    return task.isPresent() &&
                           (task.get().getStatus() == TaskStatus.RETRYING ||
                            task.get().getStatus() == TaskStatus.FAILED);
                });

        // Wait for final FAILED status after retries are exhausted
        // The scheduler requeues RETRYING tasks every 10s, then the worker picks them up
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .until(() -> {
                    var task = taskRepository.findById(created.getId());
                    return task.isPresent() && task.get().getStatus() == TaskStatus.FAILED;
                });

        var failedTask = taskRepository.findById(created.getId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, failedTask.getStatus());
        assertNotNull(failedTask.getError());
        assertTrue(failedTask.getError().contains("Intentional test failure"));
        assertTrue(failHandlerInvocations.get() >= 2,
                "Handler should have been invoked at least 2 times (initial + 1 retry), was: " + failHandlerInvocations.get());
    }
}
