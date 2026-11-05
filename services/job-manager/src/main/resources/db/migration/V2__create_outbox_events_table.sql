
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,

    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    routing_key VARCHAR(150) NOT NULL,

    payload JSONB NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,

    CONSTRAINT fk_outbox_events_job
        FOREIGN KEY (aggregate_id)
        REFERENCES jobs(id),

    CONSTRAINT chk_outbox_events_status
        CHECK (status IN ('PENDING', 'PUBLISHED')),

    CONSTRAINT chk_outbox_events_attempts
        CHECK (attempts >= 0),

    CONSTRAINT chk_outbox_events_payload
        CHECK (jsonb_typeof(payload) = 'object'),

    CONSTRAINT chk_outbox_events_published_at
        CHECK (
            (status = 'PENDING' AND published_at IS NULL)
            OR
            (status = 'PUBLISHED' AND published_at IS NOT NULL)
        )
);

CREATE INDEX idx_outbox_events_pending
    ON outbox_events (created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_events_aggregate_id
    ON outbox_events (aggregate_id);
