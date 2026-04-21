package com.taskscheduler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Redis sorted-set wrapper for the task queue.
 * Uses ZADD for enqueue and BZPOPMIN for blocking dequeue.
 */
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    public static final String TASK_QUEUE_KEY = "task_queue";

    private final RedisTemplate<String, String> redisTemplate;

    public QueueService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Enqueue a task into the sorted set with priority as score (lower = higher urgency).
     */
    public void enqueue(UUID taskId, int priority) {
        Boolean added = redisTemplate.opsForZSet().add(TASK_QUEUE_KEY, taskId.toString(), priority);
        log.info("Enqueued task {} with priority {} (added={})", taskId, priority, added);
    }

    /**
     * Remove a task from the queue (used on cancel).
     */
    public void remove(UUID taskId) {
        Long removed = redisTemplate.opsForZSet().remove(TASK_QUEUE_KEY, taskId.toString());
        log.info("Removed task {} from queue (removed={})", taskId, removed);
    }

    /**
     * Blocking pop the highest-priority (lowest-score) task from the queue.
     * Uses BZPOPMIN with a timeout. Returns the task ID or null if timeout.
     *
     * Spring Data Redis does not expose BZPOPMIN directly through ZSetOperations,
     * so we use the Lettuce native API.
     */
    public UUID blockingPop(long timeoutSeconds) {
        try {
            // Use Lettuce native connection for BZPOPMIN
            LettuceConnectionFactory connectionFactory = (LettuceConnectionFactory) redisTemplate.getConnectionFactory();
            if (connectionFactory == null) {
                log.error("Redis connection factory is null");
                return null;
            }

            // Execute BZPOPMIN via RedisTemplate execute
            return redisTemplate.execute(connection -> {
                // Use raw Redis connection to execute BZPOPMIN
                Object result = connection.execute("BZPOPMIN",
                        TASK_QUEUE_KEY.getBytes(),
                        String.valueOf(timeoutSeconds).getBytes());

                if (result == null) {
                    return null;
                }

                // BZPOPMIN returns a list: [key, member, score]
                if (result instanceof java.util.List<?> list && list.size() >= 2) {
                    Object memberObj = list.get(1);
                    String member;
                    if (memberObj instanceof byte[] bytes) {
                        member = new String(bytes);
                    } else {
                        member = memberObj.toString();
                    }
                    return UUID.fromString(member);
                }

                return null;
            }, true);
        } catch (Exception e) {
            log.warn("BZPOPMIN failed or timed out: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the current queue size via ZCARD.
     */
    public long queueSize() {
        Long size = redisTemplate.opsForZSet().zCard(TASK_QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * Get all task IDs currently in the queue (for debugging/monitoring).
     */
    public Set<String> getAllQueued() {
        return redisTemplate.opsForZSet().range(TASK_QUEUE_KEY, 0, -1);
    }
}
