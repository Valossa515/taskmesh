package io.github.valossa515.jobmanager.presentation.rest.exception;

import io.github.valossa515.jobmanager.application.job.execution.ExecutionClaimService;
import io.github.valossa515.jobmanager.application.job.execution.ExecutionOutcomeService;
import io.github.valossa515.jobmanager.application.job.create.JobTypeAdmission;
import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String detail = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField()
                        + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");

        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                detail,
                "validation-error",
                request
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleMalformedRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid Request Body",
                "Request body is malformed or contains invalid field types",
                "invalid-request-body",
                request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode status = exception.getStatusCode();

        String detail = status.is5xxServerError()
                ? "An unexpected error occurred"
                : exception.getReason() != null
                ? exception.getReason()
                : "Request could not be processed";

        return buildProblem(
                status,
                status.is5xxServerError()
                        ? "Internal Server Error"
                        : "Request Failed",
                detail,
                status.is5xxServerError()
                        ? "internal-error"
                        : "request-error",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred",
                "internal-error",
                request
        );
    }

    private ResponseEntity<ProblemDetail> buildProblem(
            HttpStatusCode status,
            String title,
            String detail,
            String type,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detail
        );

        problem.setTitle(title);
        problem.setType(
                URI.create("urn:taskmesh:problem:" + type)
        );
        problem.setInstance(
                URI.create(request.getRequestURI())
        );

        return ResponseEntity
                .status(status)
                .body(problem);
    }


    @ExceptionHandler(
            ExecutionClaimService.ExecutionAlreadyClaimedException.class
    )
    public ResponseEntity<ProblemDetail> handleExecutionActive(
            ExecutionClaimService.ExecutionAlreadyClaimedException exception,
            HttpServletRequest request
    ) {
        var response = buildProblem(
                HttpStatus.CONFLICT,
                "Execution Active",
                exception.getMessage(),
                "execution-active",
                request
        );

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "5")
                .body(response.getBody());
    }

    @ExceptionHandler(
            ExecutionClaimService.SubmissionMismatchException.class
    )
    public ResponseEntity<ProblemDetail> handleSubmissionMismatch(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Submission Mismatch",
                exception.getMessage(),
                "submission-mismatch",
                request
        );
    }


    @ExceptionHandler({
            ExecutionClaimService.UnsupportedJobTypeException.class,
            JobTypeAdmission.UnsupportedJobTypeException.class
    })
    public ResponseEntity<ProblemDetail> handleUnsupportedJobType(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Unsupported Job Type",
                exception.getMessage(),
                "unsupported-job-type",
                request
        );
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Job Not Found",
                exception.getMessage(),
                "job-not-found",
                request
        );
    }


    @ExceptionHandler(
            ExecutionOutcomeService.ExecutionStaleException.class
    )
    public ResponseEntity<ProblemDetail> handleExecutionStale(
            ExecutionOutcomeService.ExecutionStaleException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Execution Stale",
                exception.getMessage(),
                "execution-stale",
                request
        );
    }

    @ExceptionHandler(
            ExecutionOutcomeService.OutcomeConflictException.class
    )
    public ResponseEntity<ProblemDetail> handleOutcomeConflict(
            ExecutionOutcomeService.OutcomeConflictException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Outcome Conflict",
                exception.getMessage(),
                "outcome-conflict",
                request
        );
    }


    @ExceptionHandler(
            WorkerInstanceRegistrationService
                    .WorkerSessionExpiredException.class
    )
    public ResponseEntity<ProblemDetail> handleWorkerSessionExpired(
            WorkerInstanceRegistrationService
                    .WorkerSessionExpiredException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Worker Session Expired",
                exception.getMessage(),
                "worker-session-expired",
                request
        );
    }


    @ExceptionHandler(
            org.springframework.security.access.AccessDeniedException.class
    )
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.FORBIDDEN,
                "Access Denied",
                "Worker access is forbidden",
                "worker-access-denied",
                request
        );
    }
}