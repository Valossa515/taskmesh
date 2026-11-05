# ADR-003: Shared Job Queue with Competing Consumers

- **Status:** Accepted
- **Date:** 2026-11-05
- **Milestone:** 02 — Distributed Job Processing
- **Decision owners:** TaskMesh maintainers
- **Scope:** Job submission and dispatch from `job-manager` to Java and .NET workers

## Context

TaskMesh currently creates Jobs through `POST /api/v1/jobs` and persists them in PostgreSQL with an initial `PENDING` status. The platform needs to dispatch work asynchronously to independently deployed Java and .NET workers without coupling the messaging contract to either language.

The repository already includes RabbitMQ in Docker Compose and a sample `contracts/examples/job-submitted.json`. The sample is **not yet a validated schema**, and publishing/consuming is **not implemented**. The existing Spring Courier library handles **in-process command dispatch only**; RabbitMQ provides cross-process communication.

We must decide whether both worker implementations receive jobs from one queue or each implementation receives jobs through separate queues.

## Decision

Use a **shared durable RabbitMQ work queue with competing consumers** for the first distributed processing workflow.

Initial logical topology:

| Resource | Name | Configuration / purpose |
| --- | --- | --- |
| Exchange | `taskmesh.jobs` | Durable `topic` exchange |
| Routing key | `job.submitted` | Identifies a submitted job event |
| Queue | `taskmesh.jobs.pending` | Durable queue for executable Jobs |
| Binding | `taskmesh.jobs` → `taskmesh.jobs.pending` | Binding key `job.submitted` |
| Consumers | `worker-java`, `worker-dotnet` | Independently deployed consumers of the **same queue** |

Illustrative flow:

```text
HTTP POST /api/v1/jobs
        |
        v
job-manager  --> PostgreSQL (Job: PENDING)
        |
        | Job + OutboxEvent committed atomically
        v
PostgreSQL outbox_events
        |
        | scheduled publisher (future implementation)
        v
RabbitMQ topic exchange: taskmesh.jobs
        |
        | binding: job.submitted
        v
Shared queue: taskmesh.jobs.pending
        |                    |
        +--------------------+
        |                    |
        v                    v
  worker-java          worker-dotnet
   (consumer)           (consumer)
```

Workers **compete** for deliveries: a given delivery is assigned to one consumer at a time, rather than broadcast to both technologies. RabbitMQ can redeliver unacknowledged messages, so a given Job may be processed more than once unless the application prevents duplicate effects.

**Constraint:** Every consumer attached to this queue must support every job type the queue can contain. We will begin with a language-neutral, deterministic test job supported by both workers. Workers must not consume from this queue if they cannot execute all accepted job types.

## Message contract boundary

The transport format will be language-neutral JSON, with explicit `eventId`, `eventType`, `schemaVersion`, `occurredAt`, and `data` fields. The `data` section identifies the Job (`jobId`, `jobType`) and carries its JSON `payload`.

A formal JSON Schema will be introduced under `contracts/schemas/`, and both Java and .NET implementations must validate or test compatibility against it. The existing example also contains `priority` and `maxAttempts`; these fields do **not** imply that priority scheduling or retries are implemented. Their behavior must be specified before being treated as operational guarantees.

`jobId` represents the business work item and `eventId` identifies an emitted message/event. They serve different deduplication purposes.

## Delivery and processing semantics

1. **At-least-once processing is the design target, not exactly-once processing.** Durable messaging and acknowledgements alone do not eliminate duplicates or prevent every possible loss scenario.
2. **Manual consumer acknowledgements:** a worker acknowledges a delivery only after its required processing and durable outcome recording have succeeded. Acknowledging early risks lost work if the worker crashes afterward.
3. **Bounded concurrency:** workers will use configurable prefetch and concurrency limits so they do not accumulate unbounded unacknowledged deliveries. Exact values will be set after load testing.
4. **Redelivery is expected:** a worker crash or connection loss can cause another worker to receive the same message. Side effects therefore need idempotency keyed to the logical Job and operation.
5. **Publisher reliability:** messages should be persistent and publishers should use publisher confirms and handle unroutable messages, but confirms alone do not make database updates and message publication atomic.
6. **Ordering is not guaranteed at the application level:** competing consumers, retries, and parallel execution can reorder completion. Jobs must not assume FIFO completion.
7. **State ownership:** `job-manager` remains the authoritative owner of Job state. Workers will report started/completed/failed outcomes through defined contracts or APIs; they will not write directly to the manager's PostgreSQL tables.

## Transactional consistency — mandatory Transactional Outbox

**Decision:** implement the **Transactional Outbox from the first publishing implementation**, not as a later migration. Direct publication from `CreateJobHandler` or an HTTP controller is **not** part of the accepted design.

On job creation, a single PostgreSQL transaction commits both:

1. The `jobs` row representing a newly created `PENDING` Job.
2. An `outbox_events` row containing a stable event ID, event type, schema version, serialized payload, creation timestamp, and delivery state.

A separate outbox relay reads pending events and publishes persistent messages to RabbitMQ. A publish is not treated as safely delivered solely because a send method returned: the relay must use publisher confirms and appropriate routing feedback. It marks the outbox row as published **only after positive broker confirmation and routing verification**. An absent or negative confirmation leaves the row eligible for retry, with operational bounds and diagnostics.

A crash after RabbitMQ accepts the message but before the database records publication can cause a duplicate publish. Consequently the design provides **at-least-once event delivery under stated operational assumptions**, not exactly-once delivery. Downstream consumers must deduplicate logical work and side effects. The outbox itself also requires safe claiming/locking when multiple relay instances operate concurrently.

**Implementation boundary:** the initial outbox relay can be a simple scheduled poller; CDC, complex retry frameworks, and an HA broker are not prerequisites for the first incremental slice. No guarantee of end-to-end reliable processing is claimed until relay failure/restart and consumer acknowledgment tests are in place.

## Failure handling and future extensions

- Define retryable versus non-retryable execution failures before implementing retries.
- Introduce a dead-letter strategy (DLX / failure queue) and bounded retries; avoid infinite requeue loops.
- Add idempotent processing and state-transition concurrency control before running consequential or side-effecting workloads.
- Add observability for queue depth, unacknowledged deliveries, processing latency, failures, redeliveries, and correlation IDs.
- Evaluate quorum queues and broker high availability for production deployment; this first milestone does not assume an HA RabbitMQ cluster.
- If different Job types require different worker capabilities, evolve to **routing by capability / job type** (for example, dedicated queues bound to distinct routing keys), never solely by implementation language.

## Alternatives considered

### One queue per technology

**Pros:** easy specialization; clear Java/.NET ownership.

**Cons:** tying work routing to programming language; broadcast-like bindings can duplicate work; technology-specific routing complicates adding another worker implementation. **Rejected for the initial shared-workload scenario.**

### One queue per job type / capability

**Pros:** precise routing; specialized workers can scale independently; avoids sending unsupported jobs to consumers.

**Cons:** more queues, bindings, and operational configuration before we have meaningful capability differences. **Deferred** until workloads require specialization.

### HTTP dispatch directly to workers

**Pros:** straightforward synchronous call flow.

**Cons:** tight availability coupling, explicit worker discovery and load balancing, and weaker buffering during bursts. **Not selected** for the asynchronous work distribution path.

## Consequences

**Benefits**

- Both Java and .NET can process the same message contract.
- Horizontal scaling is possible by adding consumers of the shared queue.
- `job-manager` remains decoupled from worker location and programming language.
- A focused first implementation keeps the topology understandable and testable.

**Costs and risks**

- Workers must share a capability set for all messages in this queue.
- Duplicate deliveries require idempotent processing.
- Worker concurrency and retry behavior require careful tuning.
- Durable queues are not sufficient by themselves for end-to-end processing guarantees.
- Additional event/result contracts and failure handling are necessary before the workflow is complete.

## Decision validation — approved

- [x] Use one durable `topic` exchange named `taskmesh.jobs`, with routing key `job.submitted`.
- [x] Bind one shared durable queue named `taskmesh.jobs.pending`; Java and .NET consumers compete for messages.
- [x] Both workers must support every Job type admitted to this shared queue; begin with a simple language-neutral demonstration job.
- [x] Target **at-least-once** delivery, explicitly allowing retries and duplicate deliveries, not exactly-once effects.
- [x] Keep `job-manager` as the authoritative owner of Job state; workers report outcomes rather than accessing its tables.
- [x] Require **Transactional Outbox from the first publishing implementation**. No direct publish in the Job creation transaction.
- [x] Treat `priority` and `maxAttempts` from the sample as *not implemented*; do not imply operational support.

## Incremental implementation plan

Proceed one validated slice at a time; implementation is not authorized merely by this ADR's acceptance.

1. **Contract:** define `contracts/schemas/job-submitted.schema.json` (JSON Schema Draft 2020-12) and align `contracts/examples/job-submitted.json`. Require `eventId`, `eventType = job.submitted`, `schemaVersion = 1.0`, `occurredAt`, and `data.jobId`, `data.jobType`, `data.payload` (JSON object). Do not include `priority` or `maxAttempts` in the v1 contract until their semantics are specified. Validate valid/invalid examples automatically.
2. **Outbox persistence:** add Flyway V2 for `outbox_events`, persistence model, port/adapter, and a transaction-scoped create-Job-and-outbox operation. Verify rollback atomicity via PostgreSQL Testcontainers.
3. **RabbitMQ topology:** declare exchange, queue, and binding. Test declarations and routing against a containerized RabbitMQ instance.
4. **Outbox relay:** publish pending events with publisher confirms, persistent messages, controlled claiming, and retries; test failure/recovery and duplicate-publication windows.
5. **Workers and outcomes:** implement a common demonstration Job type in Java and .NET, manual acknowledgement, idempotency, and the first outcome-report contract; test that one delivery is handled by one competing consumer at a time, with redelivery after failures.

**Next validation gate:** approve the minimal `job.submitted` v1 envelope and tests before editing application code or creating database migrations.
