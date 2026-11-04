# ADR-001: Clean Architecture and In-Process CQRS

- **Status:** Accepted
- **Date:** 2026-11-04
- **Decision makers:** TaskMesh maintainers
- **Scope:** `services/job-manager`

## Context

TaskMesh is a distributed job-orchestration platform developed incrementally. Its first service, `job-manager`, currently creates and persists jobs through an HTTP API. Later milestones will introduce RabbitMQ messages and workers written in Java and .NET.

The service needs to keep business rules independent of HTTP, database mapping, and messaging technologies. It also needs a straightforward way to coordinate application commands without coupling controllers to command handlers.

## Decision

Adopt Clean Architecture principles with explicit responsibilities:

- **Domain:** `Job` and `JobStatus` own lifecycle invariants and state transitions. Domain code does not depend on Spring, JPA, or HTTP types.
- **Application:** application operations and ports define use-case boundaries. `IJobRepository` is an application-facing persistence port.
- **Infrastructure:** `JobRepositoryAdapter`, `SpringDataJobRepository`, `JobJpaEntity`, and `JobMapper` implement persistence using Spring Data JPA and PostgreSQL.
- **Presentation:** `JobController`, HTTP DTOs, and HTTP mappers adapt external requests and responses.

Use the **Spring Courier** library as an **in-process CQRS/Mediator dispatcher** for commands. `CreateJobCommand` represents the creation request, and `CreateJobHandler` executes the use case using `IJobRepository`. Controllers dispatch through `Courier` rather than instantiate or depend on a concrete handler.

CQRS is applied pragmatically: commands are separated from their dispatch and handling. **Separate read databases, event sourcing, and asynchronous command dispatch are not required by this decision.**

The current command and handler implementation resides in `io.github.valossa515.jobmanager.job.create`. Package structure may be refined as the service grows, without changing the dependency boundaries.

## Consequences

### Benefits

- Domain rules can be unit-tested without the Spring container.
- The persistence mechanism can change behind the application port.
- Controllers stay focused on HTTP concerns.
- Additional commands can follow a consistent dispatch pattern.
- The Java application model remains independent of future cross-language integration contracts.

### Trade-offs

- Additional mapping and adapter code increases the number of classes.
- An in-process mediator adds an abstraction and depends on the Spring Courier library.
- A mediator is not, by itself, a distributed system or a message broker.
- The current repository port is intentionally small and may evolve as use cases emerge.

## Alternatives considered

1. **Controller directly invokes a service/handler.** Simpler for a small CRUD application, but less consistent with the chosen command-dispatch approach.
2. **Use JPA entities directly as the domain model.** Reduces mapping code but couples business invariants to persistence concerns.
3. **Introduce a complete asynchronous CQRS/event-sourcing architecture immediately.** Adds complexity without a demonstrated need in the Foundation milestone.

## Validation

- Domain unit tests cover lifecycle transitions and invariants.
- Mapper and persistence tests verify reconstitution and PostgreSQL `JSONB` handling.
- Spring Courier integration tests verify command dispatch and persistence.
- HTTP integration tests verify creation through `POST /api/v1/jobs`.
- **Checkpoint:** 30 tests passed locally on 2026-11-04.

## Future considerations

- Define versioned, language-neutral RabbitMQ contracts in `contracts/`.
- Keep broker publishing and consuming separate from Spring Courier dispatch.
- Reconsider application packaging and port granularity only when further use cases justify changes.

## Related decisions

- [ADR-002: HTTP Error Handling with RFC 9457 Problem Details](ADR-002-http-error-handling-with-problem-details.md)
