package com.taskscheduler.repository;

import com.taskscheduler.model.Task;
import com.taskscheduler.model.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * Atomically claim a task for execution.
     * Returns the number of rows updated (0 = already claimed by another worker).
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'RUNNING', t.startedAt = :startedAt " +
           "WHERE t.id = :id AND t.status = com.taskscheduler.model.TaskStatus.PENDING")
    int claimTask(@Param("id") UUID id, @Param("startedAt") OffsetDateTime startedAt);

    /**
     * Mark a task as completed.
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'COMPLETED', t.completedAt = :completedAt " +
           "WHERE t.id = :id")
    int completeTask(@Param("id") UUID id, @Param("completedAt") OffsetDateTime completedAt);

    /**
     * Mark a task for retry with exponential backoff.
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'RETRYING', t.retryCount = :retryCount, " +
           "t.error = :error, t.scheduledAt = :scheduledAt, t.startedAt = NULL " +
           "WHERE t.id = :id")
    int retryTask(@Param("id") UUID id,
                  @Param("retryCount") int retryCount,
                  @Param("error") String error,
                  @Param("scheduledAt") OffsetDateTime scheduledAt);

    /**
     * Mark a task as permanently failed.
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'FAILED', t.error = :error, t.completedAt = :completedAt " +
           "WHERE t.id = :id")
    int failTask(@Param("id") UUID id,
                 @Param("error") String error,
                 @Param("completedAt") OffsetDateTime completedAt);

    /**
     * Cancel a task (only if PENDING).
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'FAILED', t.error = 'Cancelled by user', t.completedAt = :completedAt " +
           "WHERE t.id = :id AND t.status = com.taskscheduler.model.TaskStatus.PENDING")
    int cancelTask(@Param("id") UUID id, @Param("completedAt") OffsetDateTime completedAt);

    /**
     * Find stale RUNNING tasks (heartbeat timeout — started more than cutoff ago).
     */
    @Query("SELECT t FROM Task t WHERE t.status = com.taskscheduler.model.TaskStatus.RUNNING " +
           "AND t.startedAt < :cutoff")
    List<Task> findStaleRunningTasks(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Reset a stale task back to PENDING for requeue.
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'PENDING', t.startedAt = NULL " +
           "WHERE t.id = :id AND t.status = com.taskscheduler.model.TaskStatus.RUNNING")
    int resetStaleToPending(@Param("id") UUID id);

    /**
     * Find RETRYING tasks whose scheduled_at has passed.
     */
    @Query("SELECT t FROM Task t WHERE t.status = com.taskscheduler.model.TaskStatus.RETRYING " +
           "AND t.scheduledAt <= :now")
    List<Task> findRetryableTasks(@Param("now") OffsetDateTime now);

    /**
     * Reset a RETRYING task to PENDING for requeue.
     */
    @Modifying
    @Query("UPDATE Task t SET t.status = 'PENDING', t.scheduledAt = NULL " +
           "WHERE t.id = :id AND t.status = com.taskscheduler.model.TaskStatus.RETRYING")
    int resetRetryToPending(@Param("id") UUID id);

    /**
     * Paginated list filtered by status.
     */
    Page<Task> findByStatus(TaskStatus status, Pageable pageable);

    /**
     * Paginated list of all tasks.
     */
    Page<Task> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
