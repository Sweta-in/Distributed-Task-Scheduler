package com.taskscheduler.service;

import com.taskscheduler.dto.TaskCreateRequest;
import com.taskscheduler.dto.TaskResponse;
import com.taskscheduler.exception.TaskNotFoundException;
import com.taskscheduler.model.Task;
import com.taskscheduler.model.TaskPriority;
import com.taskscheduler.model.TaskStatus;
import com.taskscheduler.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final QueueService queueService;
    private final Counter tasksSubmittedHighCounter;
    private final Counter tasksSubmittedMediumCounter;
    private final Counter tasksSubmittedLowCounter;

    public TaskService(TaskRepository taskRepository,
                       QueueService queueService,
                       Counter tasksSubmittedHighCounter,
                       Counter tasksSubmittedMediumCounter,
                       Counter tasksSubmittedLowCounter) {
        this.taskRepository = taskRepository;
        this.queueService = queueService;
        this.tasksSubmittedHighCounter = tasksSubmittedHighCounter;
        this.tasksSubmittedMediumCounter = tasksSubmittedMediumCounter;
        this.tasksSubmittedLowCounter = tasksSubmittedLowCounter;
    }

    /**
     * Submit a new task: persist to DB and enqueue to Redis.
     */
    @Transactional
    public TaskResponse submitTask(TaskCreateRequest request) {
        Task task = new Task();
        task.setName(request.getName());
        task.setPayload(request.getPayload());
        task.setHandler(request.getHandler());
        task.setPriority(request.getPriority());
        task.setMaxRetries(request.getMaxRetries());
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setCreatedAt(OffsetDateTime.now());
        task.setScheduledAt(request.getScheduledAt() != null ? request.getScheduledAt() : OffsetDateTime.now());

        Task saved = taskRepository.save(task);
        log.info("Task submitted: id={}, name={}, handler={}, priority={}",
                saved.getId(), saved.getName(), saved.getHandler(), saved.getPriority());

        // Enqueue to Redis sorted set
        queueService.enqueue(saved.getId(), saved.getPriority());

        // Increment the appropriate priority counter
        incrementSubmittedCounter(saved.getPriority());

        return TaskResponse.fromEntity(saved);
    }

    /**
     * Get a task by ID.
     */
    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        return TaskResponse.fromEntity(task);
    }

    /**
     * List tasks with optional status filter, paginated.
     */
    @Transactional(readOnly = true)
    public Page<TaskResponse> listTasks(TaskStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Task> taskPage;
        if (status != null) {
            taskPage = taskRepository.findByStatus(status, pageable);
        } else {
            taskPage = taskRepository.findAll(pageable);
        }

        return taskPage.map(TaskResponse::fromEntity);
    }

    /**
     * Cancel a task. Only PENDING tasks can be cancelled.
     */
    @Transactional
    public void cancelTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot cancel task " + taskId + " — current status is " + task.getStatus() +
                    ". Only PENDING tasks can be cancelled.");
        }

        int updated = taskRepository.cancelTask(taskId, OffsetDateTime.now());
        if (updated > 0) {
            queueService.remove(taskId);
            log.info("Task cancelled: id={}", taskId);
        } else {
            log.warn("Task cancel had no effect (race condition): id={}", taskId);
        }
    }

    private void incrementSubmittedCounter(int priority) {
        TaskPriority tp = TaskPriority.fromValue(priority);
        switch (tp) {
            case HIGH -> tasksSubmittedHighCounter.increment();
            case LOW -> tasksSubmittedLowCounter.increment();
            default -> tasksSubmittedMediumCounter.increment();
        }
    }
}
