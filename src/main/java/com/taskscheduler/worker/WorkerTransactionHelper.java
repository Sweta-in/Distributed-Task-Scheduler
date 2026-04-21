package com.taskscheduler.worker;

import com.taskscheduler.model.Task;
import com.taskscheduler.repository.TaskRepository;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Extracted transactional operations for the worker loop.
 * This avoids CircularBeanDependency by keeping @Transactional methods
 * in a separate bean that WorkerLoop can depend on without self-referencing.
 */
@Component
public class WorkerTransactionHelper {

    private static final Logger log = LoggerFactory.getLogger(WorkerTransactionHelper.class);

    private final TaskRepository taskRepository;
    private final Counter tasksCompletedCounter;
    private final Counter tasksFailedCounter;

    public WorkerTransactionHelper(TaskRepository taskRepository,
            Counter tasksCompletedCounter,
            Counter tasksFailedCounter) {
        this.taskRepository = taskRepository;
        this.tasksCompletedCounter = tasksCompletedCounter;
        this.tasksFailedCounter = tasksFailedCounter;
    }

    @Transactional
    public int claimTask(UUID taskId) {
        return taskRepository.claimTask(taskId, OffsetDateTime.now());
    }

    @Transactional
    public void completeTask(UUID taskId) {
        taskRepository.completeTask(taskId, OffsetDateTime.now());
        tasksCompletedCounter.increment();
    }

    @Transactional
    public void handleTaskFailure(Task task, Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

        if (task.getRetryCount() < task.getMaxRetries()) {
            int newRetryCount = task.getRetryCount() + 1;
            long delaySeconds = (long) Math.pow(2, task.getRetryCount());
            OffsetDateTime nextScheduledAt = OffsetDateTime.now().plusSeconds(delaySeconds);

            taskRepository.retryTask(task.getId(), newRetryCount, errorMessage, nextScheduledAt);
            log.warn("Task {} failed (attempt {}/{}), scheduling retry in {}s: {}",
                    task.getId(), newRetryCount, task.getMaxRetries(), delaySeconds, errorMessage);
        } else {
            failTaskPermanently(task.getId(), errorMessage);
        }
    }

    @Transactional
    public void failTaskPermanently(UUID taskId, String errorMessage) {
        taskRepository.failTask(taskId, errorMessage, OffsetDateTime.now());
        tasksFailedCounter.increment();
        log.error("Task {} permanently failed: {}", taskId, errorMessage);
    }
}
