package io.github.valossa515.jobmanager.presentation.rest.exception;

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
}