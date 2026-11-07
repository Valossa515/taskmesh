package io.github.valossa515.jobmanager.application.exception;

public class EventPublicationException extends RuntimeException {

    public EventPublicationException(String message) {
        super(message);
    }

    public EventPublicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
