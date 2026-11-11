# Java worker foundation

Milestone **03.3.1** introduces an independently buildable Spring Boot application.
It uses **Java 25**, **Spring Boot 4.1.2**, and the same **Gradle 9.8.2** wrapper
as the Job Manager. It does not consume or execute jobs yet.

## Run and validate

From `services/worker-java`, with JDK 25 available:

```powershell
.\gradlew.bat clean test bootJar --no-daemon
.\gradlew.bat bootRun
```

On Linux/macOS, use `bash ./gradlew` in place of `.\gradlew.bat`.
Startup requires neither Docker nor a running RabbitMQ, Job Manager or PostgreSQL.
The process remains alive through `spring.main.keep-alive=true`; stop it with
Ctrl+C. There is no HTTP server or health endpoint in this increment. A successful
startup demonstrates configuration and wiring, not readiness to process jobs.

The six tests cover independent startup, absence of database/topology/consumers,
stopped manual-ack containers, bounded intake, publisher safety settings and
external connection settings with an unreachable broker host.

Run the existing regression gates separately from the repository root:

```powershell
Push-Location services/job-manager
.\gradlew.bat clean test --no-daemon
Pop-Location
python -m unittest discover -s contracts/tests -p 'test_*.py' -v
```

The Job Manager's 161-test baseline and the nine contract tests remain independent.
The worker CI workflow builds the executable jar and runs its own tests; it does
not replace or filter the Job Manager workflow.

### Local validation checkpoint — 2026-11-11

Based on Job Manager commit `98a0ec9`:

- Job Manager `clean test`: **161 passed**, zero failures/errors/skips.
- Worker `clean test bootJar`: **6 passed**, zero failures/errors/skips; executable jar built.
- Shared contract suite: **9 passed**.
- Packaged jar started and remained running with `spring.rabbitmq.host=broker.invalid`;
  the smoke-test process was then stopped.
- Job Manager source/tests, contracts and infrastructure are unchanged.
- Remote CI for this increment is pending; local validation does not assert a GitHub run.

## Configuration

Use Spring Boot's `spring.rabbitmq.*` settings instead of duplicating its connection
model. Local defaults match `infrastructure/docker-compose.yml`: `localhost:5672`,
user `taskmesh`, virtual host `/`, and the development-only password fallback
`taskmesh_dev`. `RABBITMQ_PASSWORD` overrides the password. Standard overrides such
as `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`,
`SPRING_RABBITMQ_PASSWORD` and `SPRING_RABBITMQ_VIRTUAL_HOST` are also supported.
Supply appropriate secrets outside local development.

| Setting | Foundation behavior |
| --- | --- |
| `spring.rabbitmq.dynamic=false` | No automatic `AmqpAdmin` or topology declarations. |
| Simple and direct listener `auto-startup=false` | Listener startup remains disabled; no listener endpoints exist. |
| Simple listener `acknowledge-mode=manual` | Future handlers must explicitly settle individual deliveries. |
| Prefetch, concurrency and max concurrency `1` | Conservative, bounded intake for the first consumer. |
| Listener and template retries disabled | Avoid hidden retry loops outside the ADR-004 budget. |
| `default-requeue-rejected=false` | Avoid automatic immediate requeue loops; this does **not** implement quarantine. |
| Correlated confirms, publisher returns and mandatory publication | Prepare the publishing configuration; no DLQ transfer exists yet. |

Keep consumption disabled until the next validation gate. Changing a startup
property cannot create a consumer in this foundation. Future consumer code must
handle exceptions and settlement explicitly: these defaults alone cannot ensure
durable quarantine or prevent loss after an unhandled listener exception.

## Architecture and tradeoffs

- **Independent service build.** Copy the existing wrapper rather than changing
  the monorepo to a multi-project build. This isolates the regression surface at
  the cost of keeping wrapper versions synchronized manually.
- **Non-web process.** Only the AMQP starter is needed now. The future HTTP API
  interaction is outbound to the Job Manager, not an execution API hosted by the
  worker. Add HTTP client and operational endpoints when those behaviors exist.
- **Infrastructure wiring first.** Boot owns connection and container factory
  configuration. Application ports and message/HTTP adapters will be introduced
  with their first use cases; no empty domain layers, mediator or shared Java
  domain dependency are added. The cross-runtime boundary stays in `contracts/`.
- **Manager-owned state.** No JDBC, JPA, Flyway, database credentials or migrations
  belong here. Only the Job Manager grants executions and writes job state.
- **Externally provisioned topology.** The existing manager declares the durable
  topic exchange `taskmesh.jobs`, queue `taskmesh.jobs.pending` and binding
  `job.submitted`. This worker neither redeclares them nor creates a private queue.
  The separate durable `taskmesh.jobs.dead` DLQ is still absent from the inspected
  infrastructure and must be provisioned and routing verified before consumption.
- **Offline tests.** Real Boot wiring is exercised without broker/container
  dependencies. This keeps the foundation fast, but proves no message delivery,
  network authentication, confirmation handling or cross-service execution.

Spring references: [AMQP auto-configuration](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
and [application properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).

## Protocol handoff and next validation gate

The source of truth is [ADR-004](../../docs/adr/ADR-004-worker-execution-protocol.md),
the [v1 schema](../../contracts/schemas/job-submitted.schema.json), and the current
Job Manager implementation. Foundation code deliberately introduces no DTOs or
partial processing pipeline.

1. **Registration and credentials:** `POST /api/v1/worker-instances/register`
   uses HTTP Basic (`worker-java-local`, password provisioned as
   `WORKER_JAVA_PASSWORD` on the manager). It takes no identity in the body and
   returns `workerId`, `workerInstanceId`, `accessToken`, `tokenType`, `expiresAt`.
   Registration is disabled by default on the manager. Its signing key stays on
   the manager; the worker must never receive `WORKER_INSTANCE_JWT_KEY`.
2. **Execution authentication:** `POST /api/v1/jobs/{jobId}/executions/start`,
   `/{executionId}/complete` and `/{executionId}/fail` use the signed Bearer JWT.
   The manager validates its HS256 signature, issuer, audience, expiry, instance
   identity and `worker.execute` scope. Basic credentials are for registration only.
3. **Token lifetime:** the current token lasts one hour and every registration
   generates a new instance UUID. There is no renewal endpoint preserving the
   identity. Define a stop-intake/drain strategy before expiry and test failure
   recovery before enabling consumers. Re-registration during an active execution
   cannot preserve ownership, so it is not a transparent token refresh.
4. **Retry timing:** the current manager returns a fixed `Retry-After: 5` for
   `execution-active`. Review this against the 30-second lease and the shared
   eight-call ceiling, including lost responses and reconciliation. Do not assume
   this header describes the remaining lease. HTTP timeouts never mean workload
   failure, and retries must retain the original claim, execution and body.
5. **Validation and settlement:** validate the complete v1.0 schema with formats,
   exact `NO_OP` type and AMQP/envelope ID agreement. Test terminal acknowledgement,
   unknown outcomes, pause on authentication/protocol errors and confirmed,
   mandatory DLQ transfer before acknowledging the source. Keep the original bytes.

Validate this foundation and its CI before implementing registration, the consumer,
`NO_OP`, HTTP retry logic or quarantine. Broker integration, crash/redelivery and
cross-runtime acceptance tests belong to those subsequent increments.
