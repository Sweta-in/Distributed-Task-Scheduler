package com.taskscheduler.unit;

import com.taskscheduler.dto.TaskCreateRequest;
import com.taskscheduler.dto.TaskResponse;
import com.taskscheduler.exception.TaskNotFoundException;
import com.taskscheduler.model.Task;
import com.taskscheduler.model.TaskPriority;
import com.taskscheduler.model.TaskStatus;
import com.taskscheduler.repository.TaskRepository;
import com.taskscheduler.service.QueueService;
import com.taskscheduler.service.TaskService;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private QueueService queueService;

    @Mock
    private Counter tasksSubmittedHighCounter;

    @Mock
    private Counter tasksSubmittedMediumCounter;

    @Mock
    private Counter tasksSubmittedLowCounter;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                queueService,
                tasksSubmittedHighCounter,
                tasksSubmittedMediumCounter,
                tasksSubmittedLowCounter
        );
    }

    @Nested
    @DisplayName("Submit Task")
    class SubmitTaskTests {

        @Test
        @DisplayName("should persist task with correct fields and enqueue to Redis")
        void submitTask_setsCorrectFields() {
            // Arrange
            TaskCreateRequest request = new TaskCreateRequest();
            request.setName("Send Welcome Email");
            request.setPayload("{\"recipient\":\"user@example.com\"}");
            request.setHandler("emailHandler");
            request.setPriority(TaskPriority.HIGH.getValue());
            request.setMaxRetries(5);

            Task savedTask = createTask(UUID.randomUUID(), "Send Welcome Email",
                    "emailHandler", TaskPriority.HIGH.getValue(), TaskStatus.PENDING);

            when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

            // Act
            TaskResponse response = taskService.submitTask(request);

            // Assert
            assertNotNull(response);
            assertEquals("Send Welcome Email", response.getName());
            assertEquals("emailHandler", response.getHandler());
            assertEquals(TaskPriority.HIGH.getValue(), response.getPriority());
            assertEquals(TaskStatus.PENDING, response.getStatus());

            // Verify task was saved
            ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
            verify(taskRepository).save(taskCaptor.capture());
            Task captured = taskCaptor.getValue();
            assertEquals("Send Welcome Email", captured.getName());
            assertEquals("emailHandler", captured.getHandler());
            assertEquals(TaskPriority.HIGH.getValue(), captured.getPriority());
            assertEquals(TaskStatus.PENDING, captured.getStatus());
            assertEquals(0, captured.getRetryCount());
            assertEquals(5, captured.getMaxRetries());
            assertNotNull(captured.getCreatedAt());

            // Verify enqueue was called
            verify(queueService).enqueue(savedTask.getId(), TaskPriority.HIGH.getValue());

            // Verify HIGH counter was incremented
            verify(tasksSubmittedHighCounter).increment();
            verify(tasksSubmittedMediumCounter, never()).increment();
            verify(tasksSubmittedLowCounter, never()).increment();
        }

        @Test
        @DisplayName("should increment MEDIUM counter for medium priority task")
        void submitTask_mediumPriority_incrementsMediumCounter() {
            TaskCreateRequest request = new TaskCreateRequest();
            request.setName("Generate Report");
            request.setPayload("{}");
            request.setHandler("reportHandler");
            request.setPriority(TaskPriority.MEDIUM.getValue());
            request.setMaxRetries(3);

            Task savedTask = createTask(UUID.randomUUID(), "Generate Report",
                    "reportHandler", TaskPriority.MEDIUM.getValue(), TaskStatus.PENDING);

            when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

            taskService.submitTask(request);

            verify(tasksSubmittedMediumCounter).increment();
            verify(tasksSubmittedHighCounter, never()).increment();
        }

        @Test
        @DisplayName("should increment LOW counter for low priority task")
        void submitTask_lowPriority_incrementsLowCounter() {
            TaskCreateRequest request = new TaskCreateRequest();
            request.setName("Cleanup");
            request.setPayload("{}");
            request.setHandler("reportHandler");
            request.setPriority(TaskPriority.LOW.getValue());
            request.setMaxRetries(3);

            Task savedTask = createTask(UUID.randomUUID(), "Cleanup",
                    "reportHandler", TaskPriority.LOW.getValue(), TaskStatus.PENDING);

            when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

            taskService.submitTask(request);

            verify(tasksSubmittedLowCounter).increment();
        }
    }

    @Nested
    @DisplayName("Cancel Task")
    class CancelTaskTests {

        @Test
        @DisplayName("should cancel a PENDING task and remove from queue")
        void cancelTask_pending_succeeds() {
            UUID taskId = UUID.randomUUID();
            Task task = createTask(taskId, "Test Task", "emailHandler", 5, TaskStatus.PENDING);

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
            when(taskRepository.cancelTask(eq(taskId), any(OffsetDateTime.class))).thenReturn(1);

            assertDoesNotThrow(() -> taskService.cancelTask(taskId));

            verify(taskRepository).cancelTask(eq(taskId), any(OffsetDateTime.class));
            verify(queueService).remove(taskId);
        }

        @Test
        @DisplayName("should throw IllegalStateException when cancelling a RUNNING task")
        void cancelTask_running_throwsIllegalState() {
            UUID taskId = UUID.randomUUID();
            Task task = createTask(taskId, "Test Task", "emailHandler", 5, TaskStatus.RUNNING);

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> taskService.cancelTask(taskId));

            assertTrue(ex.getMessage().contains("RUNNING"));
            verify(queueService, never()).remove(any());
        }

        @Test
        @DisplayName("should throw IllegalStateException when cancelling a COMPLETED task")
        void cancelTask_completed_throwsIllegalState() {
            UUID taskId = UUID.randomUUID();
            Task task = createTask(taskId, "Test Task", "emailHandler", 5, TaskStatus.COMPLETED);

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

            assertThrows(IllegalStateException.class, () -> taskService.cancelTask(taskId));
        }

        @Test
        @DisplayName("should throw TaskNotFoundException when task does not exist")
        void cancelTask_notFound_throwsNotFound() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

            assertThrows(TaskNotFoundException.class, () -> taskService.cancelTask(taskId));
        }
    }

    @Nested
    @DisplayName("Get Task")
    class GetTaskTests {

        @Test
        @DisplayName("should return task response when task exists")
        void getTask_exists_returnsResponse() {
            UUID taskId = UUID.randomUUID();
            Task task = createTask(taskId, "Test Task", "emailHandler", 5, TaskStatus.PENDING);

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

            TaskResponse response = taskService.getTask(taskId);

            assertNotNull(response);
            assertEquals(taskId, response.getId());
            assertEquals("Test Task", response.getName());
        }

        @Test
        @DisplayName("should throw TaskNotFoundException when task does not exist")
        void getTask_notFound_throws() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

            assertThrows(TaskNotFoundException.class, () -> taskService.getTask(taskId));
        }
    }

    @Nested
    @DisplayName("List Tasks")
    class ListTasksTests {

        @Test
        @DisplayName("should filter tasks by status")
        void listTasks_withStatusFilter() {
            Task task = createTask(UUID.randomUUID(), "Task 1", "emailHandler", 5, TaskStatus.PENDING);
            Page<Task> page = new PageImpl<>(List.of(task));

            when(taskRepository.findByStatus(eq(TaskStatus.PENDING), any(Pageable.class))).thenReturn(page);

            Page<TaskResponse> result = taskService.listTasks(TaskStatus.PENDING, 0, 50);

            assertEquals(1, result.getTotalElements());
            assertEquals(TaskStatus.PENDING, result.getContent().get(0).getStatus());
            verify(taskRepository).findByStatus(eq(TaskStatus.PENDING), any(Pageable.class));
        }

        @Test
        @DisplayName("should return all tasks when no status filter provided")
        void listTasks_noFilter() {
            Task task1 = createTask(UUID.randomUUID(), "Task 1", "emailHandler", 1, TaskStatus.PENDING);
            Task task2 = createTask(UUID.randomUUID(), "Task 2", "reportHandler", 5, TaskStatus.COMPLETED);
            Page<Task> page = new PageImpl<>(List.of(task1, task2));

            when(taskRepository.findAll(any(Pageable.class))).thenReturn(page);

            Page<TaskResponse> result = taskService.listTasks(null, 0, 50);

            assertEquals(2, result.getTotalElements());
            verify(taskRepository).findAll(any(Pageable.class));
            verify(taskRepository, never()).findByStatus(any(), any());
        }
    }

    // ---- Helper ----

    private Task createTask(UUID id, String name, String handler, int priority, TaskStatus status) {
        Task task = new Task();
        task.setId(id);
        task.setName(name);
        task.setHandler(handler);
        task.setPriority(priority);
        task.setStatus(status);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        task.setCreatedAt(OffsetDateTime.now());
        return task;
    }
}
