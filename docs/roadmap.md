# TaskMesh roadmap

| Milestone | Status | Delivered or planned outcome |
| --- | --- | --- |
| 01 — Foundation | Complete | Create a job through the HTTP API and persist its initial state in PostgreSQL. |
| 02 — Distributed Job Processing | Complete | Dispatch a versioned submission through the outbox to RabbitMQ and observe publication. See the [architecture and validation record](architecture/job-dispatch.md). |
| 03 — Distributed Workers | Planned | Execute a shared demonstration job in Java and .NET and report its outcome to the Job Manager. |

## Milestone 03 — Distributed Workers

**Input and ownership.** Both workers will compete for deliveries on `taskmesh.jobs.pending` and support the same deterministic `NO_OP` workload. Use the existing [submission schema](../contracts/schemas/job-submitted.schema.json) and [example](../contracts/examples/job-submitted.json) as compatibility fixtures. The Job Manager owns job state. Workers will report outcomes through a versioned interface.

**Decisions before consumption.** Specify how the manager accepts started, completed and failed reports, including correlation by `eventId` and `jobId`, duplicate reports, and recovery from a worker crash after a job starts. The current API accepts arbitrary nonblank job types. Define admission or routing for unsupported types and a bounded disposition for invalid messages before attaching consumers to a populated queue.

**Acceptance criteria**

- Java and .NET each consume the v1.0 fixture and execute `NO_OP`. With both attached, one consumer handles each delivery. Configure bounded prefetch and concurrency.
- A successful run persists `PENDING → RUNNING → COMPLETED`. A controlled execution failure persists `RUNNING → FAILED` with a reason. Workers use the agreed reporting interface.
- Acknowledge manually after the outcome is durable. Exercise a crash before acknowledgement and a repeated publication with the same event ID. Redelivery must preserve one logical outcome and avoid repeating effects for the same job.
- Exercise malformed, unsupported and incompatible-version messages. Verify their agreed disposition and that another valid job can proceed without an infinite requeue loop.

Production retry policies, broker high availability, capability-specific queues, distributed tracing and the Angular monitoring UI remain future work. The [shared-queue ADR](adr/ADR-003-shared-job-queue-with-competing-consumers.md) records the delivery assumptions for this milestone.
