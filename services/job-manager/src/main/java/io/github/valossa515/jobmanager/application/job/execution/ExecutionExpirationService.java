
package io.github.valossa515.jobmanager.application.job.execution;

import io.github.valossa515.jobmanager.application.port.IDatabaseClock;
import io.github.valossa515.jobmanager.application.port.IJobExecutionRepository;
import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.JobExecution;
import io.github.valossa515.jobmanager.domain.model.JobExecutionStatus;
import io.github.valossa515.jobmanager.domain.model.JobStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExecutionExpirationService {

    private final IJobRepository jobs;
    private final IJobExecutionRepository executions;
    private final IDatabaseClock clock;

    public ExecutionExpirationService(
            IJobRepository jobs,
            IJobExecutionRepository executions,
            IDatabaseClock clock
    ) {
        this.jobs = jobs;
        this.executions = executions;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");

        // Lock Job first, following the same order
        // as start, complete and fail.
        Job job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Execution references a missing job"
                ));

        JobExecution execution = executions
                .findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Job execution not found"
                ));

        // Another transaction may have already
        // completed, failed or expired this execution.
        if (execution.getStatus()
                != JobExecutionStatus.ACTIVE) {
            return false;
        }

        if (job.getStatus() != JobStatus.RUNNING) {
            throw new IllegalStateException(
                    "Active execution requires a RUNNING job"
            );
        }

        // The authoritative time is sampled only
        // after the Job lock has been acquired.
        Instant now = clock.now();

        if (now.isBefore(execution.getExpiresAt())) {
            return false;
        }

        execution.expire(now);

        job.fail(
                JobExecution.LEASE_EXPIRED_REASON,
                now
        );

        // Both updates belong to the same transaction.
        jobs.save(job);
        executions.save(execution);

        return true;
    }
}