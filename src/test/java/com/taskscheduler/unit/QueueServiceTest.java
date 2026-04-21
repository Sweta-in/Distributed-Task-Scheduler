package com.taskscheduler.unit;

import com.taskscheduler.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        queueService = new QueueService(redisTemplate);
    }

    @Test
    @DisplayName("enqueue should call ZADD with correct key, task ID, and priority score")
    void enqueue_callsZAddWithCorrectParams() {
        UUID taskId = UUID.randomUUID();
        int priority = 1; // HIGH

        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        queueService.enqueue(taskId, priority);

        verify(zSetOperations).add(
                eq(QueueService.TASK_QUEUE_KEY),
                eq(taskId.toString()),
                eq((double) priority)
        );
    }

    @Test
    @DisplayName("enqueue should use priority as score (lower = higher urgency)")
    void enqueue_usesCorrectScore() {
        UUID taskId = UUID.randomUUID();

        when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(true);

        // HIGH priority = 1
        queueService.enqueue(taskId, 1);
        verify(zSetOperations).add(QueueService.TASK_QUEUE_KEY, taskId.toString(), 1.0);

        // MEDIUM priority = 5
        UUID taskId2 = UUID.randomUUID();
        queueService.enqueue(taskId2, 5);
        verify(zSetOperations).add(QueueService.TASK_QUEUE_KEY, taskId2.toString(), 5.0);

        // LOW priority = 10
        UUID taskId3 = UUID.randomUUID();
        queueService.enqueue(taskId3, 10);
        verify(zSetOperations).add(QueueService.TASK_QUEUE_KEY, taskId3.toString(), 10.0);
    }

    @Test
    @DisplayName("remove should call ZREM with correct key and task ID")
    void remove_callsZRemWithCorrectParams() {
        UUID taskId = UUID.randomUUID();

        when(zSetOperations.remove(anyString(), any())).thenReturn(1L);

        queueService.remove(taskId);

        verify(zSetOperations).remove(
                eq(QueueService.TASK_QUEUE_KEY),
                eq(taskId.toString())
        );
    }

    @Test
    @DisplayName("remove should handle task that is not in queue gracefully")
    void remove_taskNotInQueue_noException() {
        UUID taskId = UUID.randomUUID();

        when(zSetOperations.remove(anyString(), any())).thenReturn(0L);

        assertDoesNotThrow(() -> queueService.remove(taskId));
        verify(zSetOperations).remove(QueueService.TASK_QUEUE_KEY, taskId.toString());
    }

    @Test
    @DisplayName("queueSize should return ZCARD value")
    void queueSize_returnsZCardValue() {
        when(zSetOperations.zCard(QueueService.TASK_QUEUE_KEY)).thenReturn(42L);

        long size = queueService.queueSize();

        assertEquals(42L, size);
        verify(zSetOperations).zCard(QueueService.TASK_QUEUE_KEY);
    }

    @Test
    @DisplayName("queueSize should return 0 when ZCARD returns null")
    void queueSize_returnsZeroWhenNull() {
        when(zSetOperations.zCard(QueueService.TASK_QUEUE_KEY)).thenReturn(null);

        long size = queueService.queueSize();

        assertEquals(0L, size);
    }
}
