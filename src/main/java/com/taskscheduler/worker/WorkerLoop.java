package com.taskscheduler.worker;

import com.taskscheduler.model.Task;
import com.taskscheduler.repository.TaskRepository;
import com.taskscheduler.service.QueueService;
import com.taskscheduler.worker.handlers.TaskHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker loop that continuously pops tasks from the Redis queue and executes them.
 * Runs in a dedicated thread pool via TaskExecutor. Multiple instances can run concurrently
 * for horizontal scaling.
 */
@Component
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final long BLOCKING_TIMEOUT_SECONDS = 5;
    private static final int WORKER_COUNT = 3;

    private final QueueService queueService;
    private final TaskRepository taskRepository;
    private final ApplicationContext applicationContext;
    private final TaskExecutor taskExecutor;
    private final WorkerTransactionHelper txHelper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, TaskHandler> handlerRegistry = new ConcurrentHashMap<>();

    public WorkerLoop(QueueService queueService,
                      TaskRepository taskRepository,
                      ApplicationContext applicationContext,
                      TaskExecutor taskExecutor,
                      WorkerTransactionHelper txHelper) {
        this.queueService = queueService;
        this.taskRepository = taskRepository;
        this.applicationContext = applicationContext;
        this.taskExecutor = taskExecutor;
        this.txHelper = txHelper;
    }

    /**
     * Start workers after the application context is fully initialized.
     * Using ApplicationReadyEvent avoids circular dependency issues that
     * occur with @PostConstruct self-proxy patterns.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startWorkers() {
        // Build handler registry from all TaskHandler beans
        Map<String, TaskHandler> handlers = applicationContext.getBeansOfType(TaskHandler.class);
        for (TaskHandler handler : handlers.values()) {
            handlerRegistry.put(handler.handlerName(), handler);
            log.info("Registered task handler: {}", handler.handlerName());
        }

        running.set(true);

        // Start multiple worker threads via the TaskExecutor
        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i;
            taskExecutor.execute(() -> runWorker(workerId));
        }

        log.info("Started {} worker threads", WORKER_COUNT);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down worker loop...");
        running.set(false);
    }

    /**
     * Main worker loop — runs in its own thread from the TaskExecutor pool.
     * Continuously pops tasks from Redis queue and processes them.
     */
    private void runWorker(int workerId) {
        String workerName = "worker-" + workerId;
        MDC.put("workerId", workerName);
        log.info("Worker {} started", workerName);

        try {
            while (running.get()) {
                try {
                    processNextTask(workerName);
                } catch (Exception e) {
                    log.error("Worker {} encountered unexpected error in main loop", workerName, e);
                    // Brief pause to avoid tight error loop
                    Thread.sleep(1000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Worker {} interrupted, shutting down", workerName);
        } finally {
            MDC.remove("workerId");
            log.info("Worker {} stopped", workerName);
        }
    }

    /**
     * Process a single task from the queue.
     */
    private void processNextTask(String workerName) {
        // 1. BZPOPMIN — blocking pop with timeout
        UUID taskId = queueService.blockingPop(BLOCKING_TIMEOUT_SECONDS);
        if (taskId == null) {
            // Timeout — no tasks available, loop back
            return;
        }

        MDC.put("taskId", taskId.toString());
        try {
            log.info("Worker {} dequeued task {}", workerName, taskId);

            // 2. Atomically claim the task in Postgres
            int claimed = txHelper.claimTask(taskId);
            if (claimed == 0) {
                log.info("Task {} already claimed by another worker, skipping", taskId);
                return;
            }

            // 3. Load the task to get handler name and payload
            Optional<Task> taskOpt = taskRepository.findById(taskId);
            if (taskOpt.isEmpty()) {
                log.warn("Task {} not found in database after claiming", taskId);
                return;
            }

            Task task = taskOpt.get();

            // 4. Resolve handler bean by name
            TaskHandler handler = handlerRegistry.get(task.getHandler());
            if (handler == null) {
                String errorMsg = "No handler found for: " + task.getHandler();
                log.error(errorMsg);
                txHelper.failTaskPermanently(taskId, errorMsg);
                return;
            }

            // 5. Execute the handler
            try {
                handler.execute(taskId, task.getPayload());

                // Success — mark as COMPLETED
                txHelper.completeTask(taskId);
                log.info("Task {} completed successfully by {}", taskId, workerName);
            } catch (Exception e) {
                // 6. Handle failure — retry or fail permanently
                txHelper.handleTaskFailure(task, e);
            }
        } finally {
            MDC.remove("taskId");
        }
    }
}
