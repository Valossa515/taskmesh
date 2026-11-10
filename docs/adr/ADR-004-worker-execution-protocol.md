# ADR-004: Worker execution protocol for NO_OP jobs

- **Status:** Proposed for maintainer review
- **Date:** 2026-11-10
- **Scope:** Milestone 03, increment 03.1. Protocol for the Job Manager and the Java and .NET consumers of `taskmesh.jobs.pending`.

## Context

The Job Manager commits a job and its submission in one transaction. The outbox row ID is the envelope's `eventId` and the AMQP message ID. Its `aggregate_id` is the envelope's `data.jobId`. A consumer can receive the event before the relay records `PUBLISHED`, and the relay can publish the same event again. [Dispatch architecture](../architecture/job-dispatch.md) · [ADR-003](ADR-003-shared-job-queue-with-competing-consumers.md)

`Job.start()` accepts only `PENDING`. Both `complete()` and `fail(reason)` require `RUNNING`. `JobMapper` reconstitutes rows through `Job.restore`, which checks the presence and order of the lifecycle timestamps and requires a nonblank reason exclusively for `FAILED`. Flyway V1 stores this lifecycle. V2 and V3 store the outbox and **publisher** reservations. The job JPA entity has no execution token or version, and the current repository's read and save operations provide no atomic claim. [Job domain](../../services/job-manager/src/main/java/io/github/valossa515/jobmanager/domain/model/Job.java) · [JPA mapping](../../services/job-manager/src/main/java/io/github/valossa515/jobmanager/infrastructure/persistence/JobMapper.java) · [Migrations](../../services/job-manager/src/main/resources/db/migration)

## Admission and ownership

Both workers accept the exact, case-sensitive type `NO_OP`. They ignore the contents of its object payload and report completion without external side effects. A controlled failure in tests exercises the failure path. Before enabling either consumer, restrict every create-job command to that type. The existing API accepts other nonblank types. The proposed `POST /api/v1/jobs` returns 422 for a well-formed unsupported type, before inserting either row. Malformed bodies, blank types and missing or non-object payloads retain 400. Existing jobs keep their recorded state. Quarantine queued submissions for formerly accepted types and reconcile them by ID before any manual replay. [Submission schema](../../contracts/schemas/job-submitted.schema.json) · [Shared fixture](../../contracts/examples/job-submitted.json)

The broker assigns a delivery. Only the Job Manager can grant an execution, change job state or assign lifecycle timestamps. Workers call its internal HTTP interface and have no database credentials. Each process has a unique instance ID bound to its authenticated worker identity. A restarted process gets a fresh instance ID. Only authorized workers may use these routes.

## HTTP contract

All three commands use JSON and the prefix `/api/v1/jobs/{jobId}/executions`. IDs are UUIDs. A worker generates a fresh `claimId` per delivery handler and reuses it for HTTP retries within that handler. The manager generates the opaque `executionId` on grant. A delivery tag, language, worker name or event ID cannot serve as the token.

| Command | Required JSON fields | Successful response after commit |
| --- | --- | --- |
| `POST …/start` | `eventId`, `claimId` | 200 with `jobId`, `status: RUNNING`, `executionId` and UTC `expiresAt` for the holder. While the grant is live, a repeat by the same instance, event and claim returns the same grant and deadline. A valid submission for a terminal job returns 200 with `jobId`, terminal `status` and the stored `failureReason` if failed. |
| `POST …/{executionId}/complete` | `eventId` | 200 with the committed `jobId` and `status: COMPLETED`. |
| `POST …/{executionId}/fail` | `eventId`, nonblank string `reason` | 200 with the committed `jobId`, `status: FAILED` and stored `failureReason`. Use this command only for a known workload failure. |

Before granting or disclosing a terminal outcome, verify that the persisted submission is a `job.submitted` event for the path job and that the stored job is admitted. Accept a matching outbox row in either publication state. Retain this event-to-job association through the supported redelivery and DLQ replay window. The fixture IDs require a matching persisted submission in end-to-end tests.

The `jobId` is the unit of execution. Another legitimate submission ID for the same job observes the same active or terminal job. An unknown event, or an event belonging to a different job, grants no rights even if the target job is terminal. Bind each grant to the original event, claim and worker instance. Only its holder may report an outcome. A handler executes once after a grant and retries an uncertain report with the original body. A new delivery begins with `start`, including after loss of the broker acknowledgement.

## Atomicity, fencing and crash recovery

1. In a short PostgreSQL transaction, lock the job row by primary key using `SELECT … FOR UPDATE`. Every lifecycle writer, including recovery, takes this lock before reading execution state. Re-read the current state under the lock, verify the identifiers and apply one decision. Release the lock at commit, before the worker runs. [Architecture boundaries](ADR-001-clean-architecture-and-cqrs.md)
2. For a valid claim on `PENDING`, call `Job.start()` and insert a manager-owned execution record in the same transaction. A future additive migration must enforce one record per job (job primary/foreign key), a unique execution ID, and persist the grant's event, claim, owner and deadline. Retain the record with its terminal disposition, including whether the manager expired it, for replay comparison. JPA must round-trip the lifecycle through `restore`. A rollback leaves neither a grant nor a partial state change.
3. For `RUNNING`, only the holder of the matching, unexpired grant can complete or fail. Persist the job and execution disposition together. An identical terminal report by the winning holder returns the original success, even after the deadline. Compare the operation and, for failure, the exact reason. A conflicting operation or reason returns 409. No replay rewrites a timestamp or reason.
4. Use a fixed, configurable lease for the short `NO_OP`, initially 30 seconds. Sample database time **after acquiring the lock**. At or after the deadline, the grant loses authority. On the first valid command encountering an expired active execution, or in a periodic manager sweep, atomically call `Job.fail("EXECUTION_LEASE_EXPIRED")` and record manager expiry. Commit recovery even when the triggering report receives a conflict. A `start` then returns the terminal observation. The sweep must resume after a manager restart and also handles jobs with no further delivery. A late worker cannot overwrite recovery. Workers receive no lease renewal or second grant in this milestone.
5. Both terminal paths preserve the first `startedAt` and set `completedAt`. Manager-assigned transition times must satisfy `createdAt <= startedAt <= completedAt` even across manager instances. Before enabling the protocol, inspect pre-existing rows. Historical terminal rows may be observed through a valid submission. Reconcile any legacy `RUNNING` row without a grant under the same lock to `FAILED` with an explicit migration reason before workers start. Never fabricate a live holder.

A deadline closes a job even if the worker finished locally but lost contact before recording the result. We accept that cost for a short demonstration with no side effects. Automatic takeover would need another attempt, a fresh fence and rules for repeating effects. A token protects writes to the manager. Any future external effect needs its own idempotency contract keyed by job and operation. HTTP lets either runtime learn the committed decision directly, at the cost of depending on manager availability when claiming and finishing.

## Failure handling and broker settlement

Workers validate JSON, the full v1.0 schema (including formats), the admitted job type and agreement between AMQP message ID and envelope ID before calling the manager. Invalid JSON, incompatible versions, invalid identifiers and unsupported types go straight to quarantine. They cause no job transition. Both clients use manual, individual acknowledgements and bounded prefetch and concurrency.

Errors follow [ADR-002 Problem Details](ADR-002-http-error-handling-with-problem-details.md). Branch on status and stable problem `type` under `urn:taskmesh:problem:`. The following suffixes and responses are proposed for the execution routes.

| Observation | Worker action |
| --- | --- |
| Confirmed terminal 200 from a result or from `start` | Acknowledge this delivery. A terminal observation requires no workload execution. |
| 409 `execution-active` on a competing claim | Hold the delivery, do no work, and repeat `start` after the manager's `Retry-After` (by lease expiry). Never expose another holder's token. |
| 409 `execution-stale` on wrong holder, wrong token or expired result | Stop executing or reporting that attempt. Reconcile via `start` for the original submission. A terminal observation settles the delivery. An active holder follows the wait rule. |
| 409 `outcome-conflict` on a contradictory terminal report | Preserve the first result and quarantine the contradictory report with the delivery for investigation. |
| 400 invalid input, 404 `job-not-found`, 409 `submission-mismatch` (unknown or unrelated event), 422 `unsupported-job-type` | Quarantine with the problem type. Creation committed before dispatch, so an authoritative missing job is an integrity error. |
| Timeout, disconnect, 408, 429 or 5xx, including commit followed by a lost response | Treat the result as unknown. Retry the same command, token and body with backoff and jitter, honoring `Retry-After`. Never translate a transport failure into a workload failure. |
| 401/403, unexpected response shape, unknown status or problem type | Pause consumption and surface the configuration or protocol error. Keep outstanding deliveries unsettled for recovery after correction. |

For 03, budget one initial call and at most three delayed retries per command per delivery (initial backoffs 1, 2 and 4 seconds). Count active-claim waits and stale-result reconciliation against a shared ceiling of eight HTTP calls per delivery. Exhaustion sends the original delivery and last command/outcome category to quarantine for reconciliation. HTTP retries never rerun the workload. Application code never immediately requeues on errors.

Quarantine means publishing the original bytes and diagnostic metadata to a **separate durable DLQ** (`taskmesh.jobs.dead`) using a persistent, mandatory publication to the default exchange with the DLQ name as its routing key. Acknowledge the source delivery only after positive confirmation and verification that it was routed. This records a durable exception disposition distinct from a successful job outcome. On a return, nack, timeout or lost DLQ confirmation, keep the source unsettled, pause intake and alert. Resume after the route or dependency recovers. A crash in this transfer can duplicate DLQ entries. Preserve event and job IDs when available, and retain malformed bytes even when neither ID can be parsed. Provision the DLQ and verify its route before connecting consumers.

Retry budgets bound application retries on one delivery. Broker redelivery after process or channel loss can start another budget. Repeated consumer crashes require pausing the failing consumer and inspecting the retained delivery. DLQ replay is explicit after reconciliation with the manager. A quarantined delivery can correspond to a pending job, an active lease or an already committed result.

## Validation gate

Maintainers should review the expiry-to-failure policy, the admission change and the quarantine procedure before authorizing the next increment. The following are future acceptance tests.

| Boundary | Required evidence |
| --- | --- |
| HTTP and database | Race Java and .NET claims on one job using independent connections. Verify one grant, bounded competing waits, same-claim replay, rejection of a different owner, event/job mismatch and a second valid event for the same job. Inject rollback between job and grant writes. |
| Lifecycle and migration | Restore each state after commit. Cover legacy rows, nonblank failure reasons, clock order and unchanged timestamps on replay. Race complete versus fail versus sweep on both sides of expiry. Verify exactly one durable outcome and a resumed sweep after a crash with no redelivery. |
| Cross-runtime and transport | Run the shared fixture in each runtime. Drop the response after start and after terminal commit. Kill a worker before start, after start and after commit before ack. Republish the same event. Verify eventual settlement and one logical outcome in each case. |
| Admission and quarantine | Prove unsupported creation leaves no job or outbox row. Feed legacy types, malformed bytes, mismatched IDs and incompatible versions ahead of a valid job. Exercise retry exhaustion, broken DLQ routing, duplicate quarantine transfer and recovery from a manager outage. Verify no early ack and that the valid job proceeds once dependencies recover. |
