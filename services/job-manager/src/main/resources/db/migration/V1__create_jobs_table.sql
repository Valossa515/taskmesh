
CREATE TABLE jobs (
                      id UUID PRIMARY KEY,
                      type VARCHAR(100) NOT NULL,
                      payload JSONB NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      created_at TIMESTAMPTZ NOT NULL,
                      started_at TIMESTAMPTZ,
                      completed_at TIMESTAMPTZ,
                      failure_reason TEXT,

                      CONSTRAINT chk_jobs_status
                          CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_jobs_status
    ON jobs (status);

CREATE INDEX idx_jobs_created_at
    ON jobs (created_at);
