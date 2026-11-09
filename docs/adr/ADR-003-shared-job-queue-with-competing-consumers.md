# ADR-003: Shared Job Queue with Competing Consumers

- **Status:** Accepted
- **Date:** 2026-11-05
- **Milestone:** 02 — Distributed Job Processing
- **Decision owners:** TaskMesh maintainers
- **Scope:** Job submission and dispatch from `job-manager` to Java and .NET workers

## Context

When this decision was made, the Job Manager could persist jobs and the repository contained a sample message. The open question was how to dispatch work asynchronously to independently deployed Java and .NET workers using one language-neutral contract.

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
| Consumers | `worker-java`, `worker-dotnet` | Planned competing consumers of the **same queue** |

The [dispatch architecture](../architecture/job-dispatch.md) describes the implemented producer path. The consumers in the topology are planned for Milestone 03.

Workers **compete** for deliveries: a given delivery is assigned to one consumer at a time, rather than broadcast to both technologies. RabbitMQ can redeliver unacknowledged messages, so a given Job may be processed more than once unless the application prevents duplicate effects.

**Constraint:** Every consumer attached to this queue must support every job type the queue can contain. We will begin with a language-neutral, deterministic test job supported by both workers. Workers must not consume from this queue if they cannot execute all accepted job types.

## Message contract boundary

The [job.submitted v1.0 schema](../../contracts/schemas/job-submitted.schema.json) defines the envelope and job data. Both worker implementations must pass compatibility tests against it. Priority scheduling and execution retry limits require separate semantics.

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

Commit the job and its submission event in the same PostgreSQL transaction. Publishing directly from the handler would allow a broker send to survive a database rollback, or a committed job to lose its dispatch if the process stopped before sending.

Use short reservations with `FOR UPDATE SKIP LOCKED` and expiring leases. Concurrent relays can claim different rows and release database locks before waiting on RabbitMQ. A fresh token per reservation fences the final update, even if a publisher restarts with the same name. Lease expiry permits recovery after a crash and delays the next attempt after a failed send.

Require a correlated positive publisher confirm and check for a mandatory return. RabbitMQ can acknowledge an unroutable publication, so the return check matters. Only a publisher holding the current, unexpired token can then record `PUBLISHED`.

This supports at-least-once dispatch while the relay runs and PostgreSQL, the broker and routing recover. Losing the confirm or crashing after routing and before the database update can publish the same event again. Its stable event ID lets consumers recognize repeats. Polling also adds dispatch latency and database work. Consumer acknowledgements and idempotent execution belong to the worker milestone.

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
- [x] Keep priority and execution retry limits outside the v1.0 submission contract.

## Implementation status

Milestone 02 delivered the schema, PostgreSQL outbox, queue topology and scheduled relay. See the [validation record](../architecture/job-dispatch.md#validation-checkpoint) for the evidence and the [roadmap](../roadmap.md) for the worker acceptance criteria.
