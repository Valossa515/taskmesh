# Job dispatch

Milestone 02 ends when a submission reaches the RabbitMQ queue and the relay records publication. The job and its outbox event have separate lifecycles.

| Step | What happens | Durable result |
| --- | --- | --- |
| Submit | `POST /api/v1/jobs` dispatches a command to `CreateJobHandler`. The handler saves the job and its versioned `job.submitted` envelope in one PostgreSQL transaction. | HTTP 201 follows commit. Both rows start as `PENDING`. A rollback removes both writes. |
| Reserve | The relay selects one eligible event at a time, ordered by `created_at, id`, using `FOR UPDATE SKIP LOCKED`. | The reservation transaction records `locked_by`, `locked_until` and a fresh `lease_token`. The row stays `PENDING`. |
| Publish | The relay sends the stored JSON to the durable topic exchange `taskmesh.jobs` with routing key `job.submitted`. The durable queue `taskmesh.jobs.pending` is bound to that key. | The AMQP message ID matches the stable envelope `eventId`. Each send attempt has its own correlation ID. |
| Record | After a positive confirm with no return, the relay updates the pending event by ID and unexpired lease token. | The matching row becomes `PUBLISHED`, receives `published_at` and releases the lease. The job remains `PENDING`. |

A negative confirm, timeout, send error or mandatory return leaves the event pending for another reservation after expiry. If the final update affects zero rows, the relay records a lost lease. The [publication decision](../adr/ADR-003-shared-job-queue-with-competing-consumers.md#transactional-consistency--mandatory-transactional-outbox) explains the duplicate-publication window.

## Scheduler and observability

Scheduling is opt-in. Set `TASKMESH_PUBLISHER_SCHEDULING_ENABLED=true` before starting the Job Manager. The initial delay and the delay after each run both default to 5 seconds. Each batch attempts up to 10 events, claiming each immediately before sending it. The default lease is 30 seconds, the confirm timeout is 10 seconds and the startup check requires the lease to cover that timeout plus a 5-second safety margin. See the [service configuration](../../services/job-manager/src/main/resources/application.yaml).

Micrometer exposes publisher metrics at `/actuator/prometheus`. The [Prometheus configuration](../../infrastructure/prometheus/prometheus.yml) scrapes the host service every 15 seconds. The [provisioned Grafana dashboard](../../infrastructure/grafana/dashboards/outbox-publisher.json) shows three 15-minute increases and a five-minute average batch duration.

| Metric | Interpretation |
| --- | --- |
| `taskmesh_outbox_published_total` | Events successfully marked published in PostgreSQL. |
| `taskmesh_outbox_failed_total` | Publication attempts that raised a publication error. |
| `taskmesh_outbox_lease_lost_total` | Broker publication succeeded but the lease-guarded update was rejected. |
| `taskmesh_outbox_batch_duration_seconds_sum` and `_count` | Elapsed time and number of batch runs, including empty batches. |

## Validation checkpoint

On November 9, 2026, the local Java reports recorded **100 tests passed**, with zero failures, errors or skips. The [successful Job Manager CI run for `a406b0b`](https://github.com/Valossa515/taskmesh/actions/runs/42652274755) ran the Python contract checks and the Java unit and integration suite. Coverage includes transaction commit and rollback, concurrent reservations, stale lease tokens, broker confirms and returns, scheduled delivery and metric exposition.

The operational smoke test was reported successful with five `NO_OP` submissions. Each job was accepted as `PENDING`, the corresponding outbox events reached `PUBLISHED`, messages reached the shared queue, and the publication activity appeared in Prometheus and Grafana.

To repeat the check, follow the [local startup instructions](../../README.md#run-locally) with the scheduler enabled. Record the queue depth and publication counter before submitting five jobs, and keep the IDs returned by the API. For those IDs, join `jobs.id` to `outbox_events.aggregate_id` and check both statuses. With no consumers or other traffic, the ready count should rise by five. Inspect queue counts in RabbitMQ Management without fetching messages. After a scrape, compare the raw published counter across the run on the same service instance. Grafana's windowed increases can be fractional due to extrapolation.
