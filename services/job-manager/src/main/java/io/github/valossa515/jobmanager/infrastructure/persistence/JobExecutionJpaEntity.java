
package io.github.valossa515.jobmanager.infrastructure.persistence;

import io.github.valossa515.jobmanager.domain.model.JobExecutionStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_executions")
public class JobExecutionJpaEntity {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "execution_id", nullable = false, unique = true)
    private UUID executionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "worker_id", nullable = false, length = 150)
    private String workerId;

    @Column(name = "worker_instance_id", nullable = false, length = 150)
    private String workerInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobExecutionStatus status;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    protected JobExecutionJpaEntity() {
    }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID claimId) { this.claimId = claimId; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getWorkerInstanceId() {
        return workerInstanceId;
    }
    public void setWorkerInstanceId(String workerInstanceId) {
        this.workerInstanceId = workerInstanceId;
    }

    public JobExecutionStatus getStatus() { return status; }
    public void setStatus(JobExecutionStatus status) {
        this.status = status;
    }

    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) {
        this.grantedAt = grantedAt;
    }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
