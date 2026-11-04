# TaskMesh

**Distributed Job Orchestration — Java + .NET**

TaskMesh is a portfolio project for designing, building, and operating a distributed job-processing platform. It explores domain modeling, Clean Architecture, CQRS/Mediator, relational persistence, messaging, concurrency, resilience, and observability.

> **Current status:** Milestone 01 — Foundation. The Java `job-manager` supports creating and persisting jobs through an HTTP API. Distributed execution by workers is **not implemented yet**.

## Architecture

```text
HTTP client
    |
    v
POST /api/v1/jobs
    |
    v
JobController (presentation)
    |
    +-- HTTP DTO / mapper / Problem Details
    |
    v
Spring Courier (CQRS dispatcher)
    |
    v
CreateJobCommand -> CreateJobHandler (application)
    |
    v
IJobRepository (application port)
    |
    v
JobRepositoryAdapter -> Spring Data JPA (infrastructure)
    |
    v
PostgreSQL
```

The `Job` domain model is independent of Spring and JPA. `JobJpaEntity` represents the database row, and `JobMapper` converts between persistence and domain representations. Flyway owns schema changes; Hibernate validates the schema rather than generating it.

Spring Courier handles **in-process** command dispatch. Future RabbitMQ messages will be **cross-service integration contracts**, independent of Spring Courier and usable by Java and .NET workers.

## Technology stack

| Area | Technology | Current use |
| --- | --- | --- |
| Java service | Java 25, Spring Boot 4.1.2 | `job-manager` |
| Build | Gradle | Java service build and tests |
| CQRS / Mediator | [Spring Courier 15.0.0](https://github.com/Valossa515/spring-courier) | `CreateJobCommand` / handler dispatch |
| Database | PostgreSQL 17 | Job persistence |
| Migrations | Flyway | `V1__create_jobs_table.sql` |
| ORM | Spring Data JPA / Hibernate | Persistence adapter |
| Validation / errors | Jakarta Validation, Spring MVC `ProblemDetail` | HTTP input validation and RFC 9457 errors |
| Testing | JUnit, Mockito, Spring MockMvc, Testcontainers | Unit and integration tests |
| Messaging | RabbitMQ 4 | Infrastructure available; publishing/consuming jobs planned |
| Workers | Java and .NET 10 | Planned |
| UI | Angular | Planned |
| Observability | OpenTelemetry, Prometheus, Grafana | Planned |

## Monorepo layout

```text
taskmesh/
├── services/
│   ├── job-manager/                 # Implemented Spring Boot service
│   ├── worker-java/                 # Planned
│   └── worker-dotnet/               # Planned
├── contracts/                      # Planned language-neutral event schemas
├── infrastructure/
│   └── docker-compose.yml          # PostgreSQL and RabbitMQ
├── docs/
│   ├── architecture/                # Architecture documentation (in progress)
│   └── adr/                         # Architecture decision records (planned)
└── README.md
```

Inside `services/job-manager`, the source is organized around `domain`, `application`, `infrastructure`, and `presentation` responsibilities. The creation command/handler currently live under `io.github.valossa515.jobmanager.job.create`.

## Requirements

- **JDK 25** available to Gradle (the project uses a Java toolchain).
- **Docker** with Docker Compose and support for Testcontainers.
- A terminal; examples below use **PowerShell** on Windows.
- The project's Gradle Wrapper (`gradlew.bat`); a global Gradle installation is not required.

## Run locally

### 1. Start infrastructure

From the **repository root**:

```powershell
docker compose -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

The local development configuration exposes:

| Service | Host endpoint | Development credentials |
| --- | --- | --- |
| PostgreSQL | `127.0.0.1:5433` | Database: `taskmesh`; user: `taskmesh`; password: `taskmesh_dev` |
| RabbitMQ (AMQP) | `localhost:5672` | User: `taskmesh`; password: `taskmesh_dev` |
| RabbitMQ Management | <http://localhost:15672> | Same RabbitMQ credentials |

> The PostgreSQL container listens internally on `5432`; its **host** port is `5433` to avoid a local `5432` connection conflict encountered during development. Check your `infrastructure/docker-compose.yml` and `application.yml` if your checkout differs. These credentials are for **local development only**.

### 2. Start the job-manager

From the repository root:

```powershell
cd services/job-manager
.\gradlew.bat bootRun
```

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

Verified project checkpoint: **30 tests passing** on November 4, 2026. This is a test-result checkpoint, not a claim of 100% coverage.

## Milestones

| Milestone | Scope | Status |
| --- | --- | --- |
| 01 — Foundation | Monorepo, domain state machine, PostgreSQL/Flyway, JPA, Spring Courier, create-job API, tests | Implemented; documentation and final review in progress |
| 02 — Distributed processing | RabbitMQ job events, language-neutral contracts, Java/.NET workers | Planned |
| 03 — Reliability | Retry policies, idempotency, outbox, concurrency control, failure recovery | Planned |
| 04 — Observability and operations | OpenTelemetry, metrics, dashboards, operational tooling | Planned |
| 05 — User interface | Angular administration / job monitoring UI | Planned |

The milestone scope may evolve as requirements are validated. Development proceeds incrementally: design, implement, test, document, and only then expand scope.

## Architecture decisions and next steps

- **Clean Architecture:** business invariants belong to the pure Java `Job` domain model; external frameworks stay in outer layers.
- **CQRS / Mediator:** Spring Courier dispatches in-process commands through typed handlers; it is **not** the RabbitMQ transport.
- **Persistence:** Flyway controls schema evolution, while the application uses an abstract repository port and an infrastructure adapter.
- **HTTP errors:** Problem Details offers a consistent, machine-readable error format.
- **Integration testing:** Testcontainers avoids reliance on a manually provisioned database.

These decisions should be formalized as ADRs in `docs/adr/`; ADR files are not assumed to exist yet.

**Immediate next steps:** finish the Foundation documentation and manual smoke test, then design versioned job-submission/completion event schemas before implementing distributed workers. RabbitMQ is running locally but **no worker currently consumes jobs**.

## License

License for TaskMesh has not been specified yet. The [Spring Courier](https://github.com/Valossa515/spring-courier) project is a separate dependency with its own license.
