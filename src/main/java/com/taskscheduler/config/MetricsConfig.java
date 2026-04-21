package com.taskscheduler.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class MetricsConfig {

    private static final String TASK_QUEUE_KEY = "task_queue";

    @Bean
    public Counter tasksSubmittedHighCounter(MeterRegistry registry) {
        return Counter.builder("tasks.submitted")
                .description("Total tasks submitted")
                .tag("priority", "HIGH")
                .register(registry);
    }

    @Bean
    public Counter tasksSubmittedMediumCounter(MeterRegistry registry) {
        return Counter.builder("tasks.submitted")
                .description("Total tasks submitted")
                .tag("priority", "MEDIUM")
                .register(registry);
    }

    @Bean
    public Counter tasksSubmittedLowCounter(MeterRegistry registry) {
        return Counter.builder("tasks.submitted")
                .description("Total tasks submitted")
                .tag("priority", "LOW")
                .register(registry);
    }

    @Bean
    public Counter tasksCompletedCounter(MeterRegistry registry) {
        return Counter.builder("tasks.completed")
                .description("Total tasks completed successfully")
                .register(registry);
    }

    @Bean
    public Counter tasksFailedCounter(MeterRegistry registry) {
        return Counter.builder("tasks.failed")
                .description("Total tasks that permanently failed")
                .register(registry);
    }

    @Bean
    public Gauge taskQueueDepthGauge(MeterRegistry registry, RedisTemplate<String, String> redisTemplate) {
        return Gauge.builder("task.queue.depth", () -> {
                    try {
                        Long size = redisTemplate.opsForZSet().zCard(TASK_QUEUE_KEY);
                        return size != null ? size.doubleValue() : 0.0;
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .description("Current depth of the task queue in Redis")
                .register(registry);
    }
}
