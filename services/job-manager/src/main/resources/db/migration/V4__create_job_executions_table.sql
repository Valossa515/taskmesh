
CREATE TABLE job_executions (
    job_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE,
    event_id UUID NOT NULL,
    claim_id UUID NOT NULL,

    worker_id VARCHAR(150) NOT NULL,
    worker_instance_id VARCHAR(150) NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,

    failure_reason TEXT,

    CONSTRAINT fk_job_executions_job
        FOREIGN KEY (job_id)
        REFERENCES jobs(id),

    CONSTRAINT fk_job_executions_event
        FOREIGN KEY (event_id)
        REFERENCES outbox_events(id),

    CONSTRAINT chk_job_executions_status
        CHECK (status IN (
            'ACTIVE',
            'COMPLETED',
            'FAILED',
            'EXPIRED'
        )),

    CONSTRAINT chk_job_executions_deadline
        CHECK (expires_at > granted_at),

    CONSTRAINT chk_job_executions_finished_at
        CHECK (
            finished_at IS NULL
            OR finished_at >= granted_at
        ),

    CONSTRAINT chk_job_executions_disposition
        CHECK (
            (
                status = 'ACTIVE'
                AND finished_at IS NULL
                AND failure_reason IS NULL
            )
            OR
            (
                status = 'COMPLETED'
                AND finished_at IS NOT NULL
                AND failure_reason IS NULL
            )
            OR
            (
                status = 'FAILED'
                AND finished_at IS NOT NULL
                AND failure_reason IS NOT NULL
                AND length(trim(failure_reason)) > 0
            )
            OR
            (
                status = 'EXPIRED'
                AND finished_at IS NOT NULL
                AND failure_reason = 'EXECUTION_LEASE_EXPIRED'
            )
        )
);

CREATE INDEX idx_job_executions_active_deadline
    ON job_executions (expires_at, job_id)
    WHERE status = 'ACTIVE';
