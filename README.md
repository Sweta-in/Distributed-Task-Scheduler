# Distributed Task Scheduler

> A production-grade, horizontally scalable task scheduling system with priority queues, exactly-once execution, and full observability.

![Java](https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?style=flat-square&logo=springboot)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=flat-square&logo=redis)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker)
![Build](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)

---

## Overview

This system solves the problem of reliable, distributed background task execution at scale. Tasks are submitted via a REST API, persisted to PostgreSQL, and enqueued into a Redis sorted set that functions as a priority queue. Stateless worker threads consume tasks via blocking pop, claim them atomically in Postgres to guarantee exactly-once execution, and handle failures with configurable exponential backoff retries. The architecture is designed for horizontal scaling — deploy N worker instances via `docker compose --scale` with zero coordination overhead — and includes production-grade observability through Prometheus metrics, Grafana dashboards, and structured JSON logging with per-task MDC context.

---

## Architecture

```
                                ┌─────────────────────────────┐
                                │       Prometheus :9090       │
                                │    (scrapes /actuator/       │
                                │     prometheus every 10s)    │
                                └──────────┬──────────────────┘
                                           │ PromQL
                                           ▼
                                ┌─────────────────────────────┐
                                │       Grafana :3000          │
                                │  (queue depth, throughput,   │
                                │   failure rate dashboards)   │
                                └─────────────────────────────┘

  ┌──────────┐     HTTP/JSON      ┌──────────────────┐
  │  Client  │ ─────────────────► │   TaskController  │
  │ (curl /  │     POST/GET/      │   (REST API)      │
  │  app)    │     DELETE         └────────┬─────────┘
  └──────────┘                             │
                                           │ @Transactional
                                           ▼
                                ┌──────────────────┐       ZADD (priority score)
                                │   TaskService    │ ──────────────────────────────┐
                                │  (submit, get,   │                               │
                                │   list, cancel)  │                               ▼
                                └────────┬─────────┘                    ┌─────────────────┐
                                         │ JPA save                    │  Redis Sorted Set │
                                         ▼                             │  "task_queue"     │
                                ┌──────────────────┐                   │  score = priority │
                                │   PostgreSQL     │                   └────────┬──────────┘
                                │   (tasks table)  │                            │
                                │                  │ ◄──── JDBC ────┐           │ BZPOPMIN
                                └──────────────────┘                │           │ (5s timeout)
                                         ▲                          │           ▼
                                         │                 ┌────────┴──────────────────┐
                                         │  UPDATE status  │      WorkerLoop (x N)     │
                                         └─────────────────│  ┌─────────────────────┐  │
                                                           │  │ 1. BZPOPMIN         │  │
                                                           │  │ 2. Atomic claim     │  │
                                                           │  │ 3. Resolve handler  │  │
                                                           │  │ 4. Execute          │  │
                                                           │  │ 5. Complete/Retry   │  │
                                                           │  └─────────────────────┘  │
                                                           └───────────────────────────┘

                                ┌──────────────────┐
                                │ SchedulerService │  @Scheduled
                                │  (every 30s)     │──► Reclaim stale RUNNING tasks > 5min
                                │  (every 10s)     │──► Requeue RETRYING tasks past scheduled_at
                                └──────────────────┘
```

---

## Features

| Feature | Description |
|---------|-------------|
| **Priority Scheduling** | Tasks scored as HIGH(1), MEDIUM(5), LOW(10) in a Redis sorted set — lower score = higher urgency |
| **Exactly-Once Execution** | Atomic `UPDATE … WHERE status='PENDING'` prevents duplicate processing across workers |
| **Exponential Backoff Retry** | Failed tasks retry after 2^n seconds (configurable max retries per task) |
| **Heartbeat Monitor** | `@Scheduled` job reclaims tasks stuck in `RUNNING` for > 5 minutes |
| **Horizontal Scaling** | Stateless workers — scale with `docker compose up --scale app=5` |
| **Structured JSON Logging** | Logstash encoder with MDC fields (`taskId`, `workerId`) on every log line |
| **Prometheus Metrics** | Counters for submitted/completed/failed tasks, gauge for queue depth |
| **REST API** | Paginated listing, status filtering, task cancellation |
| **Flyway Migrations** | Version-controlled schema — no `hibernate.ddl-auto` in production |
| **Testcontainers** | Integration tests run against real Redis + PostgreSQL containers |

---

## API Reference

### Endpoints

| Method | Endpoint | Description | Status |
|--------|----------|-------------|--------|
| `POST` | `/api/tasks` | Submit a new task | `201 Created` |
| `GET` | `/api/tasks/{id}` | Get task by UUID | `200 OK` / `404` |
| `GET` | `/api/tasks?status=&page=&size=` | Paginated list with optional status filter | `200 OK` |
| `DELETE` | `/api/tasks/{id}` | Cancel a PENDING task | `204 No Content` / `409` |
| `GET` | `/actuator/health` | Health check (DB + Redis) | `200 OK` |
| `GET` | `/actuator/prometheus` | Prometheus metrics endpoint | `200 OK` |

### Examples

**Submit a task:**

```bash
curl -s -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Send Welcome Email",
    "payload": "{\"recipient\":\"user@example.com\",\"subject\":\"Welcome\",\"body\":\"Hello!\"}",
    "handler": "emailHandler",
    "priority": 1,
    "maxRetries": 3
  }'
```

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "Send Welcome Email",
  "handler": "emailHandler",
  "priority": 1,
  "priorityLabel": "HIGH",
  "status": "PENDING",
  "retryCount": 0,
  "maxRetries": 3,
  "createdAt": "2026-04-21T08:15:30.123Z"
}
```

**Get task by ID:**

```bash
curl -s http://localhost:8080/api/tasks/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "Send Welcome Email",
  "status": "COMPLETED",
  "completedAt": "2026-04-21T08:15:31.456Z"
}
```

**List tasks with status filter:**

```bash
curl -s "http://localhost:8080/api/tasks?status=PENDING&page=0&size=50"
```

```json
{
  "content": [ { "id": "...", "name": "...", "status": "PENDING" } ],
  "totalElements": 12,
  "totalPages": 1,
  "number": 0,
  "size": 50
}
```

**Cancel a pending task:**

```bash
curl -s -X DELETE http://localhost:8080/api/tasks/a1b2c3d4-e5f6-7890-abcd-ef1234567890
# Returns 204 No Content
```

**Health check:**

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

**Prometheus metrics:**

```bash
curl -s http://localhost:8080/actuator/prometheus | grep tasks
# tasks_submitted_total{priority="HIGH"} 42.0
# tasks_completed_total 38.0
# tasks_failed_total 2.0
# task_queue_depth 3.0
```

---

## Data Model

### Task Entity

| Field | Type | Description |
|-------|------|-------------|
| `id` | `UUID` | Primary key, auto-generated |
| `name` | `String` | Human-readable task name (required) |
| `payload` | `String` | JSON text payload passed to the handler |
| `handler` | `String` | Spring bean name of the handler (e.g., `emailHandler`) |
| `priority` | `Integer` | Execution priority: `1`=HIGH, `5`=MEDIUM, `10`=LOW |
| `status` | `TaskStatus` | Current lifecycle state (see below) |
| `retryCount` | `int` | Number of retry attempts executed |
| `maxRetries` | `int` | Maximum allowed retries (default: 3) |
| `error` | `String` | Last error message (nullable) |
| `createdAt` | `OffsetDateTime` | Task creation timestamp |
| `scheduledAt` | `OffsetDateTime` | Next scheduled execution time (for retries) |
| `startedAt` | `OffsetDateTime` | When a worker claimed the task |
| `completedAt` | `OffsetDateTime` | When the task finished (success or permanent failure) |

### Task Status Lifecycle

| Status | Description |
|--------|-------------|
| `PENDING` | Task is queued and waiting for a worker to claim it |
| `RUNNING` | A worker has claimed the task and is executing it |
| `COMPLETED` | Task executed successfully |
| `RETRYING` | Task failed but has retries remaining; waiting for backoff delay |
| `FAILED` | Task exhausted all retries or was cancelled |

```
PENDING ──► RUNNING ──► COMPLETED
                │
                ▼
            RETRYING ──► PENDING (re-enqueued after backoff)
                │
                ▼
              FAILED (max retries exhausted)
```

---

## Getting Started

### Prerequisites

- **Java 21** (for local development / running tests)
- **Docker** and **Docker Compose** (for running the full stack)
- **Maven 3.9+** (optional — wrapper included)

### Quick Start

**1. Clone the repository:**

```bash
git clone https://github.com/yourusername/distributed-task-scheduler.git
cd distributed-task-scheduler
```

**2. Start the full stack:**

```bash
docker compose up --build
```

This starts PostgreSQL, Redis, the application, Prometheus, and Grafana.

**3. Verify the API is running:**

```bash
curl http://localhost:8080/actuator/health
```

**4. Submit a test task:**

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Task","payload":"{}","handler":"emailHandler","priority":5,"maxRetries":3}'
```

**5. Check task status:**

```bash
curl http://localhost:8080/api/tasks?status=COMPLETED
```

**6. Open monitoring dashboards:**

- **Grafana:** http://localhost:3000 (admin / admin)
- **Prometheus:** http://localhost:9090

**7. Scale workers horizontally:**

```bash
docker compose up --build --scale app=5
```

---

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL hostname |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `taskscheduler` | Database name |
| `DB_USERNAME` | `taskuser` | Database username |
| `DB_PASSWORD` | `taskpass` | Database password |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | *(empty)* | Redis password (if authentication enabled) |
| `SPRING_PROFILES_ACTIVE` | *(none)* | Set to `docker` for structured JSON logging |

Application-level settings in `application.yml`:

| Property | Default | Description |
|----------|---------|-------------|
| `spring.task.execution.pool.core-size` | `5` | Worker thread pool size |
| `spring.datasource.hikari.maximum-pool-size` | `10` | Max DB connections |
| `spring.datasource.hikari.minimum-idle` | `2` | Min idle DB connections |

Scheduler timing (in `SchedulerService.java`):

| Constant | Value | Description |
|----------|-------|-------------|
| Stale task check interval | `30s` | How often to scan for stuck RUNNING tasks |
| Stale threshold | `5 min` | How long a task can be RUNNING before reclamation |
| Retry requeue interval | `10s` | How often to requeue RETRYING tasks ready for execution |

---

## Writing a Custom Task Handler

Implement the `TaskHandler` interface and register it as a Spring `@Component`:

```java
package com.taskscheduler.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InvoiceHandler implements TaskHandler {

    private final ObjectMapper objectMapper;

    public InvoiceHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String handlerName() {
        return "invoiceHandler";  // must match the "handler" field in task submissions
    }

    @Override
    public void execute(UUID taskId, String jsonPayload) throws Exception {
        JsonNode payload = objectMapper.readTree(jsonPayload);
        String customerId = payload.get("customerId").asText();
        double amount = payload.get("amount").asDouble();

        // Your business logic here
        generateAndSendInvoice(customerId, amount);
    }

    private void generateAndSendInvoice(String customerId, double amount) {
        // Implementation
    }
}
```

Submit a task using your handler:

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Generate Invoice #1234",
    "payload": "{\"customerId\":\"C-001\",\"amount\":299.99}",
    "handler": "invoiceHandler",
    "priority": 1,
    "maxRetries": 5
  }'
```

The `WorkerLoop` automatically discovers all `TaskHandler` beans at startup and routes tasks by `handlerName()`.

---

## System Design Decisions

### Why Redis Sorted Sets for the Queue

Redis sorted sets provide O(log N) insertion and O(1) pop-min with `BZPOPMIN`, making them ideal for priority queues. Unlike Kafka or RabbitMQ, Redis requires no dedicated broker cluster, reduces operational complexity, and provides the exact primitive needed: a sorted, blocking, atomic dequeue. The trade-off is durability — Redis AOF persistence is configured, but a full message broker would provide stronger guarantees for mission-critical workflows.

### Why Atomic Postgres UPDATE for Task Claiming

Instead of distributed locks (Redis `SETNX`, Zookeeper), task claiming uses `UPDATE tasks SET status='RUNNING' WHERE id=? AND status='PENDING'`. The row-level lock guarantees exactly-once execution without external coordination. If the update affects 0 rows, the worker knows another instance already claimed the task. This approach leverages the existing Postgres instance, avoids lock management complexity, and naturally handles worker crashes (the row remains in `PENDING` or gets reclaimed by the heartbeat monitor).

### Why Exponential Backoff

Fixed retry delays cause thundering herd problems when many tasks fail simultaneously. Exponential backoff (`2^retryCount` seconds) spreads retries across time, reducing pressure on downstream systems that may be recovering from overload. Each task's `maxRetries` is independently configurable, allowing critical tasks to retry more aggressively while batch jobs fail faster.

### Why Flyway over Hibernate Auto-DDL

Hibernate's `ddl-auto=update` is not safe for production — it cannot handle column renames, data migrations, or index changes predictably. Flyway provides version-controlled, auditable, repeatable schema migrations that can be code-reviewed and tested in CI before deployment. The application validates the schema on startup (`ddl-auto=validate`) to catch mismatches early.

### Why Stateless Workers

Workers maintain no local state — all task state lives in PostgreSQL, and the queue lives in Redis. This means any worker can process any task, workers can be killed and restarted without data loss, and horizontal scaling is achieved by simply adding more instances. The heartbeat monitor ensures no task is permanently lost if a worker crashes mid-execution.

---

## Testing

### Run Unit Tests

```bash
mvn test
```

Unit tests use **Mockito** to mock the repository and Redis layers:

- `TaskServiceTest` — 11 tests covering submit, cancel, get, and list operations
- `QueueServiceTest` — 6 tests verifying ZADD, ZREM, and ZCARD interactions

### Run Integration Tests

```bash
mvn verify
```

Integration tests use **Testcontainers** to spin up real PostgreSQL and Redis instances:

- `TaskControllerIntegrationTest` — Full HTTP lifecycle: POST 201, GET by ID, GET 404, paginated list, status filter, validation errors, DELETE cancel
- `WorkerIntegrationTest` — End-to-end worker processing: submit task → worker picks up → assert COMPLETED in DB; submit failing task → assert RETRYING → assert FAILED after max retries

> **Note:** Integration tests require Docker to be running.

### Test Scenarios Covered

- Task submission persists correct fields and enqueues to Redis
- Priority counter metrics increment correctly (HIGH/MEDIUM/LOW)
- Cancel throws `IllegalStateException` for non-PENDING tasks
- Cancel throws `TaskNotFoundException` for missing tasks
- Status filter returns only matching tasks
- ZADD uses priority as score; ZREM removes on cancel
- Worker claims task atomically (0-row update = skip)
- Successful handler execution transitions to COMPLETED
- Failed handler triggers retry with exponential backoff
- Max retries exhausted transitions to FAILED

---

## Monitoring

### Prometheus Metrics

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `tasks_submitted_total` | Counter | `priority` (HIGH/MEDIUM/LOW) | Total tasks submitted |
| `tasks_completed_total` | Counter | — | Total tasks completed successfully |
| `tasks_failed_total` | Counter | — | Total tasks that permanently failed |
| `task_queue_depth` | Gauge | — | Current number of tasks in the Redis queue |

### Grafana

- **URL:** http://localhost:3000
- **Credentials:** admin / admin
- **Datasource:** Add Prometheus at `http://prometheus:9090`

Recommended dashboard panels:

- Task submission rate (by priority)
- Task completion throughput
- Failure rate and retry rate
- Queue depth over time
- Worker thread utilization

### Prometheus

- **URL:** http://localhost:9090
- **Scrape interval:** 10 seconds
- **Target:** `app:8080/actuator/prometheus`

---

## Project Structure

```
distributed-task-scheduler/
├── src/main/java/com/taskscheduler/
│   ├── TaskSchedulerApplication.java          # Spring Boot entry point (@EnableScheduling, @EnableAsync)
│   ├── config/
│   │   ├── RedisConfig.java                   # RedisTemplate bean configuration (Lettuce)
│   │   ├── DataSourceConfig.java              # JPA repositories + transaction management
│   │   └── MetricsConfig.java                 # Micrometer counters + queue depth gauge
│   ├── model/
│   │   ├── Task.java                          # JPA entity mapped to tasks table
│   │   ├── TaskStatus.java                    # Enum: PENDING, RUNNING, COMPLETED, FAILED, RETRYING
│   │   └── TaskPriority.java                  # Enum: HIGH(1), MEDIUM(5), LOW(10)
│   ├── dto/
│   │   ├── TaskCreateRequest.java             # Request DTO with Bean Validation
│   │   └── TaskResponse.java                  # Response DTO with fromEntity() factory
│   ├── repository/
│   │   └── TaskRepository.java                # JPA + @Modifying queries (claim, complete, retry, fail)
│   ├── service/
│   │   ├── TaskService.java                   # Core business logic (submit, get, list, cancel)
│   │   ├── SchedulerService.java              # Stale task reclamation + retry requeue
│   │   └── QueueService.java                  # Redis sorted set wrapper (ZADD, ZREM, BZPOPMIN)
│   ├── worker/
│   │   ├── WorkerLoop.java                    # Multi-threaded consumer loop (blocking pop → execute)
│   │   ├── WorkerTransactionHelper.java       # Transactional helper (claim, complete, retry, fail)
│   │   └── handlers/
│   │       ├── TaskHandler.java               # Handler interface (handlerName + execute)
│   │       ├── EmailHandler.java              # Email task handler implementation
│   │       └── ReportHandler.java             # Report generation handler implementation
│   ├── controller/
│   │   ├── TaskController.java                # REST API endpoints (POST, GET, DELETE)
│   │   └── HealthController.java              # Custom health endpoint with queue depth
│   └── exception/
│       ├── TaskNotFoundException.java         # 404 exception
│       └── GlobalExceptionHandler.java        # @ControllerAdvice error handling
├── src/main/resources/
│   ├── application.yml                        # HikariCP, Redis, Flyway, thread pool config
│   ├── logback-spring.xml                     # Structured JSON logging (Docker) / console (local)
│   └── db/migration/
│       └── V1__create_tasks.sql               # Flyway: tasks table + indexes
├── src/test/java/com/taskscheduler/
│   ├── unit/
│   │   ├── TaskServiceTest.java               # Mockito: submit, cancel, get, list
│   │   └── QueueServiceTest.java              # Mockito: ZADD, ZREM, ZCARD
│   └── integration/
│       ├── TaskControllerIntegrationTest.java  # Testcontainers: full API lifecycle
│       └── WorkerIntegrationTest.java          # Testcontainers: worker execution + retry
├── monitoring/
│   └── prometheus.yml                         # Prometheus scrape config
├── docker-compose.yml                         # Full stack: Postgres, Redis, App, Prometheus, Grafana
├── Dockerfile                                 # Multi-stage build (Maven → Alpine JRE)
└── pom.xml                                    # Maven: Spring Boot 3.3.5, dependencies
```

---

## Roadmap

- [ ] **Cron / Scheduled Tasks** — Support cron expressions for recurring task execution
- [ ] **Task DAG / Dependency Chaining** — Define task pipelines where task B runs after task A completes
- [ ] **Web Admin Dashboard** — Real-time task monitoring UI with search, filtering, and manual retry
- [ ] **gRPC API** — High-throughput internal task submission for service-to-service communication
- [ ] **Multi-Tenancy** — Row-level security with tenant isolation for SaaS deployments
- [ ] **Dead Letter Queue** — Route permanently failed tasks to a DLQ for manual inspection
- [ ] **Kubernetes Helm Chart** — HPA scaling based on queue depth metric, PodDisruptionBudget for workers
- [ ] **Task Timeout per Handler** — Configurable execution timeout with thread interruption

---

## License

```
MIT License

Copyright (c) 2026

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
