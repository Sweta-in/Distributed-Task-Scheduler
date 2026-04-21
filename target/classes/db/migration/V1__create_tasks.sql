CREATE TABLE IF NOT EXISTS tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)    NOT NULL,
    payload         TEXT,
    handler         VARCHAR(255)    NOT NULL,
    priority        INTEGER         NOT NULL DEFAULT 5,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    retry_count     INTEGER         NOT NULL DEFAULT 0,
    max_retries     INTEGER         NOT NULL DEFAULT 3,
    error           TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);

-- Index for scheduler service queries: find stale RUNNING tasks
CREATE INDEX idx_tasks_status_started ON tasks (status, started_at);

-- Index for scheduler service queries: find RETRYING tasks ready to requeue
CREATE INDEX idx_tasks_status_scheduled ON tasks (status, scheduled_at);

-- Index for API listing with status filter
CREATE INDEX idx_tasks_status ON tasks (status);

-- Index for creation time ordering
CREATE INDEX idx_tasks_created_at ON tasks (created_at DESC);
