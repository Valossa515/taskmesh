
ALTER TABLE outbox_events
    ADD COLUMN locked_by VARCHAR(100),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN lease_token UUID;

ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_lease
    CHECK (
        (
            locked_by IS NULL
            AND locked_until IS NULL
            AND lease_token IS NULL
        )
        OR
        (
            status = 'PENDING'
            AND locked_by IS NOT NULL
            AND length(btrim(locked_by)) > 0
            AND locked_until IS NOT NULL
            AND lease_token IS NOT NULL
        )
    );