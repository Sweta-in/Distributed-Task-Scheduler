package com.taskscheduler.worker.handlers;

import java.util.UUID;

/**
 * Interface for task handlers. Each handler implementation is a Spring @Component
 * that processes a specific type of task.
 */
public interface TaskHandler {

    /**
     * Returns the handler name that matches the "handler" field in the Task entity.
     * Used by WorkerLoop to resolve the correct handler bean.
     */
    String handlerName();

    /**
     * Execute the task logic.
     *
     * @param taskId      the UUID of the task being executed
     * @param jsonPayload the JSON payload string for the task
     * @throws Exception if the task execution fails (triggers retry logic)
     */
    void execute(UUID taskId, String jsonPayload) throws Exception;
}
