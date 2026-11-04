# ADR-002: HTTP Error Handling with RFC 9457 Problem Details

- **Status:** Accepted
- **Date:** 2026-11-04
- **Decision makers:** TaskMesh maintainers
- **Scope:** `services/job-manager` HTTP API

## Context

TaskMesh exposes an HTTP endpoint for creating jobs. Requests can fail due to invalid input, malformed JSON, application errors, or unexpected infrastructure failures. Returning inconsistent JSON shapes or exposing implementation details would complicate clients and operational troubleshooting.

The application also uses Spring Courier, which can report command-handling failures through its `Response<T>` result instead of throwing an exception. HTTP input failures, meanwhile, can be raised by Spring MVC and Bean Validation.

## Decision

Use **RFC 9457 Problem Details** as the HTTP error representation, implemented with Spring's `ProblemDetail` and a global `@RestControllerAdvice` (`GlobalExceptionHandler`).

The current implementation handles:

- `MethodArgumentNotValidException`: `400 Bad Request` for request validation failures.
- `HttpMessageNotReadableException`: `400 Bad Request` for malformed JSON or incompatible request-body field types.
- `ResponseStatusException`: preserve the explicitly selected HTTP status, while hiding internal details for server errors.
- Unexpected `Exception`: `500 Internal Server Error` with a generic client-facing message.

Problem types use stable URNs such as `urn:taskmesh:problem:validation-error`. `instance` identifies the request path. Responses use the `application/problem+json` media type.

The HTTP presentation layer remains responsible for adapting Spring Courier results: `JobResponseMapper` converts a failed `Response<Job>` into an HTTP exception, and `GlobalExceptionHandler` formats the exception as a Problem Detail. **Domain classes do not produce HTTP responses or depend on `ProblemDetail`.**

## Example

A request with a blank job type receives `400 Bad Request` and a body similar to:

```json
{
  "type": "urn:taskmesh:problem:validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "type: must not be blank",
  "instance": "/api/v1/jobs"
}
```

The specific wording of validation messages may vary with validation configuration; clients should rely primarily on status and problem type rather than parsing `detail`.

## Consequences

### Benefits

- HTTP clients receive a predictable, standards-based error structure.
- Domain and application code remain independent of HTTP status codes and JSON error formats.
- Unexpected server failures do not expose exception messages or stack traces to clients.
- Error handling is testable through the existing MockMvc integration suite.

### Trade-offs

- A broad `Exception` handler may mask framework-specific exceptions unless dedicated handlers are added.
- The initial implementation returns a first field validation error, not a structured list of all violations.
- Translating `Response<T>` into `ResponseStatusException` adds a presentation adapter and requires careful mapping of Courier failure categories.
- Generic server error responses require logging and trace correlation for effective diagnosis.

## Alternatives considered

1. **Custom error DTO.** Allows full control but creates a proprietary API contract for a solved problem.
2. **Handle each failure inside controllers.** Initially easy but duplicates formatting and error-policy decisions.
3. **Expose raw exception messages.** Useful during local debugging but unsafe and unstable as a public API contract.

## Validation

HTTP integration tests currently cover successful creation, invalid job type, missing or incorrectly typed payload, and malformed JSON. The suite also verifies the Problem Details format for relevant validation errors.

- **Checkpoint:** 30 tests passed locally on 2026-11-04.

## Follow-up work

- Test explicit Spring Courier failure responses and their HTTP status mappings.
- Add structured server-side logging and correlation/trace IDs.
- Consider structured field-error extensions for multi-field validation failures.
- Add handlers as needed for other Spring MVC exception types so expected client errors do not become generic `500` responses.
- Review whether status and error-type mapping should be centralized further as more endpoints are added.

## Related decisions

- [ADR-001: Clean Architecture and In-Process CQRS](ADR-001-clean-architecture-and-cqrs.md)
