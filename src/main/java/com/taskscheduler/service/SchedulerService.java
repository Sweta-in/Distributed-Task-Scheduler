package com.taskscheduler.service;

import com.taskscheduler.model.Task;
import com.taskscheduler.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Scheduled service that monitors task health:
 * - Detects stale RUNNING tasks (heartbeat timeout) and requeues them.
 * - Picks up RETRYING tasks whose scheduled retry time has arrived and requeues them.
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);
    private static final long STALE_THRESHOLD_MINUTES = 5;

    private final TaskRepository taskRepository;
    private final QueueService queueService;

    public SchedulerService(TaskRepository taskRepository, QueueService queueService) {
        this.taskRepository = taskRepository;
        this.queueService = queueService;
    }

    /**
     * Every 30 seconds: find RUNNING tasks that started more than 5 minutes ago
     * (presumed dead worker) and reset them to PENDING + re-enqueue.
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void detectStaleRunningTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        List<Task> staleTasks = taskRepository.findStaleRunningTasks(cutoff);

        if (staleTasks.isEmpty()) {
            return;
        }

        log.info("Found {} stale RUNNING tasks (started before {})", staleTasks.size(), cutoff);

        for (Task task : staleTasks) {
            int updated = taskRepository.resetStaleToPending(task.getId());
            if (updated > 0) {
                queueService.enqueue(task.getId(), task.getPriority());
                log.warn("Reset stale task to PENDING and requeued: id={}, name={}, startedAt={}",
                        task.getId(), task.getName(), task.getStartedAt());
            }
        }
    }

    /**
     * Every 10 seconds: find RETRYING tasks whose scheduled_at has passed
     * and re-enqueue them to Redis after resetting to PENDING.
     */
    @Scheduled(fixedRate = 10_000)
    @Transactional
    public void requeueRetryableTasks() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Task> retryTasks = taskRepository.findRetryableTasks(now);

        if (retryTasks.isEmpty()) {
            return;
        }

        log.info("Found {} RETRYING tasks ready for requeue", retryTasks.size());

        for (Task task : retryTasks) {
            int updated = taskRepository.resetRetryToPending(task.getId());
            if (updated > 0) {
                queueService.enqueue(task.getId(), task.getPriority());
                log.info("Requeued retrying task: id={}, name={}, retryCount={}",
                        task.getId(), task.getName(), task.getRetryCount());
            }
        }
    }
}
