# TaskMesh

**Distributed Job Orchestration — Java + .NET**

TaskMesh is a portfolio project for designing, building, and operating a distributed job-processing platform. It explores domain modeling, Clean Architecture, CQRS/Mediator, relational persistence, messaging, concurrency, resilience, and observability.

**Milestone 02 — Distributed Job Processing is complete.** The Job Manager accepts jobs and dispatches them to RabbitMQ. Jobs remain `PENDING` after dispatch. Milestone 03 will introduce Java and .NET workers.

## Architecture

```text
POST /api/v1/jobs → Spring Courier → CreateJobHandler
                                         |
                              PostgreSQL transaction
                                jobs + outbox_events
                                               |
                                      scheduled relay
                                               |
                                     RabbitMQ exchange
                                               |
                                     taskmesh.jobs.pending
```

Spring Courier dispatches commands inside the Job Manager. RabbitMQ carries the versioned JSON contract across service boundaries. See the [dispatch architecture and validation record](docs/architecture/job-dispatch.md) for the publisher flow and operational checks.

## Technology stack

| Area | Technology | Current use |
| --- | --- | --- |
| Java service | Java 25, Spring Boot 4.1.2 | `job-manager` |
| Build | Gradle | Java service build and tests |
| CQRS / Mediator | [Spring Courier 15.0.0](https://github.com/Valossa515/spring-courier) | `CreateJobCommand` / handler dispatch |
| Database | PostgreSQL 17 | Jobs and outbox events |
| Migrations | Flyway | Jobs, outbox events and publisher leases |
| ORM | Spring Data JPA / Hibernate | Persistence adapter |
| Validation / errors | Jakarta Validation, Spring MVC `ProblemDetail` | HTTP input validation and RFC 9457 errors |
| Testing | JUnit, Mockito, Spring MockMvc, Testcontainers | Unit and integration tests |
| Messaging | RabbitMQ 4 | Confirmed publication to the shared job queue |
| Workers | Java and .NET 10 | Planned |
| UI | Angular | Planned |
| Observability | Micrometer, Prometheus, Grafana | Outbox publisher metrics and dashboard |

## Monorepo layout

- [Job Manager](services/job-manager/) — API, domain model, persistence and publisher.
- [Contracts](contracts/) — language-neutral schemas, examples and compatibility tests.
- [Infrastructure](infrastructure/) — local Compose services and monitoring configuration.
- [Architecture decisions](docs/adr/) — service boundaries, HTTP errors, queue topology and delivery guarantees.
- [Roadmap](docs/roadmap.md) — completed milestones and the planned worker scope.

Inside `services/job-manager`, the source is organized around `domain`, `application`, `infrastructure`, and `presentation` responsibilities. The creation command/handler currently live under `io.github.valossa515.jobmanager.job.create`.

## Requirements

- **JDK 25** available to Gradle (the project uses a Java toolchain).
- **Docker** with Docker Compose and support for Testcontainers.
- A terminal; examples below use **PowerShell 7.1+** on Windows.
- The project's Gradle Wrapper (`gradlew.bat`); a global Gradle installation is not required.

## Run locally

### 1. Start infrastructure

From the **repository root**:

```powershell
# Set the local Grafana admin password in this shell before running Compose.
$env:GRAFANA_ADMIN_PASSWORD = Read-Host 'Local Grafana admin password' -MaskInput
docker compose -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

The local development configuration exposes:

| Service | Host endpoint | Development credentials |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5433` | Database: `taskmesh`; user: `taskmesh`; password: `taskmesh_dev` |
| RabbitMQ (AMQP) | `localhost:5672` | User: `taskmesh`; password: `taskmesh_dev` |
| RabbitMQ Management | <http://localhost:15672> | Same RabbitMQ credentials |
| Prometheus | <http://localhost:9090> | Local metrics explorer |
| Grafana | <http://localhost:3000/d/taskmesh-outbox> | `admin` and the configured Grafana password |

> The PostgreSQL container listens internally on `5432`; its **host** port is `5433` to avoid a local `5432` connection conflict encountered during development. Check your `infrastructure/docker-compose.yml` and `application.yaml` if your checkout differs. These credentials are for **local development only**.

### 2. Start the job-manager

From the repository root:

```powershell
cd services/job-manager
# Enable automatic dispatch for this run (disabled by default).
$env:TASKMESH_PUBLISHER_SCHEDULING_ENABLED = "true"
.\gradlew.bat bootRun
```

After stopping the service, run `Remove-Item Env:TASKMESH_PUBLISHER_SCHEDULING_ENABLED` in the same shell to restore the default for subsequent launches.

The API runs at <http://localhost:8080> by default. The configured database URL for local development is `jdbc:postgresql://127.0.0.1:5433/taskmesh`. The application connects to PostgreSQL and applies Flyway migrations on startup.

Environment-variable overrides configured by the application:

```powershell
$env:DB_PASSWORD = "taskmesh_dev"
$env:RABBITMQ_PASSWORD = "taskmesh_dev"
```

The default values above are development-only fallbacks; use appropriate secrets outside local development.

### 3. Create a job

**Endpoint:** `POST /api/v1/jobs`

```powershell
$body = @{
    type = "REPORT_GENERATION"
    payload = @{
        reportId = "report-123"
        format = "PDF"
    }
} | ConvertTo-Json -Depth 5

Invoke-RestMethod `
    -Uri "http://localhost:8080/api/v1/jobs" `
    -Method Post `
    -ContentType "application/json" `
    -Body $body
```

Example successful response (**HTTP 201 Created**):

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "type": "REPORT_GENERATION",
  "status": "PENDING",
  "createdAt": "2026-11-04T14:00:00Z"
}
```

The response includes a `Location` header in the form `/api/v1/jobs/{id}`. The corresponding **GET endpoint is not implemented yet**. IDs and timestamps in the example are illustrative.

The request requires a nonblank `type` and a JSON **object** in `payload` (`{}` is permitted). Invalid requests receive HTTP `400`. Errors use **RFC 9457 Problem Details**, for example:

```json
{
  "type": "urn:taskmesh:problem:validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "type: must not be blank",
  "instance": "/api/v1/jobs"
}
```

### 4. Inspect persisted jobs

From any terminal with Docker access:

```powershell
docker exec taskmesh-postgres psql -U taskmesh -d taskmesh -c "SELECT id, type, status, jsonb_typeof(payload) AS payload_type FROM jobs;"
```

The payload is stored as PostgreSQL `JSONB`, and the initial status is `PENDING`.

### 5. Stop infrastructure

From the repository root:

```powershell
docker compose -f infrastructure/docker-compose.yml down
```

This stops the containers **without deleting volumes**. Avoid `down -v` unless you explicitly intend to remove persisted development data.

## Domain model

A Job has an ID, type, JSON payload, status, creation time, optional start/completion timestamps, and an optional failure reason.

Initial state transitions:

```text
PENDING --start()--> RUNNING --complete()--> COMPLETED
                         \
                          +--fail(reason)--> FAILED
```

- All newly created jobs begin in `PENDING`.
- `FAILED` represents an **execution** failure and is only reachable from `RUNNING`.
- `COMPLETED` and `FAILED` are terminal in the initial model.
- Invalid transitions are rejected by the domain model.
- Reconstituted jobs must satisfy state and timestamp invariants.
- Cancellation, retries, pre-execution failure states, and worker scheduling are not implemented yet.

## Tests

From `services/job-manager`:

```powershell
# Complete suite
.\gradlew.bat clean test

# Domain unit tests
.\gradlew.bat test --tests "*JobTest"

# Repository integration tests
.\gradlew.bat test --tests "*JobRepositoryIntegrationTest"

# Courier dispatch integration tests
.\gradlew.bat test --tests "*CreateJobCourierIntegrationTest"

# HTTP integration tests
.\gradlew.bat test --tests "*JobControllerIntegrationTest"
```

Integration tests use a shared test configuration (`IntegrationTestConfig`) to provision PostgreSQL through **Testcontainers** and apply Flyway migrations. Docker must be running, but the local Compose PostgreSQL service does **not** need to be started for these tests.

The [Milestone 02 validation record](docs/architecture/job-dispatch.md#validation-checkpoint) covers the Java test results, CI run and operational smoke test.

## Milestones

The [roadmap](docs/roadmap.md) tracks Milestones 01–02 and defines the scope and acceptance criteria for **Milestone 03 — Distributed Workers**.

## License

License for TaskMesh has not been specified yet. The [Spring Courier](https://github.com/Valossa515/spring-courier) project is a separate dependency with its own license.
