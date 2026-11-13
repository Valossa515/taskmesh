
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.execution.ExecutionApiClient;
import io.github.valossa515.workerjava.execution.ExecutionApiException;
import io.github.valossa515.workerjava.execution.ExecutionFailureKind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Recovery;

class JobDeliveryProcessorTest {

    private static final Instant NOW =
            Instant.parse("2030-01-01T00:00:00Z");

    private static final UUID EVENT_ID = UUID.fromString(
            "8f7c4c8e-81b5-4b65-a60a-123456789abc"
    );

    private static final UUID JOB_ID = UUID.fromString(
            "a8c2f1a0-1b2c-4d5e-8f90-123456789abc"
    );

    private static final UUID EXECUTION_ID =
            UUID.randomUUID();

    private ExecutionApiClient executionApi;
    private DeadLetterPublisher deadLetterPublisher;
    private JobDeliveryProcessor processor;
    private Message validMessage;

    @BeforeEach
    void setUp() throws Exception {
        executionApi = mock(ExecutionApiClient.class);
        deadLetterPublisher =
                mock(DeadLetterPublisher.class);

        var validator = new JobSubmittedMessageValidator(
                JsonMapper.builder().build()
        );

        processor = new JobDeliveryProcessor(
                validator,
                executionApi,
                deadLetterPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        try (var stream = getClass().getResourceAsStream(
                "/contracts/examples/job-submitted.json"
        )) {
            assertNotNull(stream);

            MessageProperties properties =
                    new MessageProperties();

            properties.setMessageId(EVENT_ID.toString());

            validMessage = new Message(
                    stream.readAllBytes(),
                    properties
            );
        }
    }

    private void startRunning() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenReturn(
                new ExecutionApiClient.StartResult(
                        JOB_ID,
                        ExecutionApiClient.JobStatus.RUNNING,
                        EXECUTION_ID,
                        NOW.plusSeconds(30),
                        null
                )
        );
    }

    private void completeSuccessfully() {
        when(executionApi.complete(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID
        )).thenReturn(
                new ExecutionApiClient.OutcomeResult(
                        JOB_ID,
                        ExecutionApiClient.JobStatus.COMPLETED,
                        null
                )
        );
    }

    private ExecutionApiException failure(
            ExecutionFailureKind kind,
            int status,
            String problemType
    ) {
        return new ExecutionApiException(
                kind,
                status,
                problemType,
                null,
                null
        );
    }

    @Test
    void shouldCompleteValidNoOp() {
        startRunning();
        completeSuccessfully();

        assertDoesNotThrow(
                () -> processor.process(validMessage)
        );

        verify(executionApi).start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        );

        verify(executionApi).complete(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID
        );

        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void shouldAcceptAlreadyCompletedJob() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenReturn(
                new ExecutionApiClient.StartResult(
                        JOB_ID,
                        ExecutionApiClient.JobStatus.COMPLETED,
                        null,
                        null,
                        null
                )
        );

        assertDoesNotThrow(
                () -> processor.process(validMessage)
        );

        verify(executionApi, never()).complete(
                any(), any(), any()
        );
    }

    @Test
    void shouldAcceptAlreadyFailedJob() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenReturn(
                new ExecutionApiClient.StartResult(
                        JOB_ID,
                        ExecutionApiClient.JobStatus.FAILED,
                        null,
                        null,
                        "EXECUTION_LEASE_EXPIRED"
                )
        );

        assertDoesNotThrow(
                () -> processor.process(validMessage)
        );

        verify(executionApi, never()).complete(
                any(), any(), any()
        );
    }

    @Test
    void shouldQuarantineMalformedMessage() {
        Message malformed = new Message(
                "{invalid".getBytes(StandardCharsets.UTF_8),
                new MessageProperties()
        );

        assertDoesNotThrow(
                () -> processor.process(malformed)
        );

        verify(deadLetterPublisher).publish(
                same(malformed),
                eq("MALFORMED_JSON")
        );

        verifyNoInteractions(executionApi);
    }

    @Test
    void shouldNotSucceedWhenQuarantineFails() {
        Message malformed = new Message(
                new byte[] {(byte) 0xFF},
                new MessageProperties()
        );

        doThrow(
                new QuarantinePublicationException(
                        QuarantinePublicationException
                                .Failure.TIMEOUT,
                        "No broker confirmation"
                )
        ).when(deadLetterPublisher).publish(
                any(Message.class),
                anyString()
        );

        assertThrows(
                QuarantinePublicationException.class,
                () -> processor.process(malformed)
        );

        verifyNoInteractions(executionApi);
    }

    @Test
    void shouldDeferTransientStartFailure() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenThrow(
                failure(
                        ExecutionFailureKind.TRANSIENT,
                        503,
                        null
                )
        );

        var exception = assertThrows(
                JobProcessingDeferredException.class,
                () -> processor.process(validMessage)
        );

        assertEquals(
                Recovery.RETRY_SAME_COMMAND,
                exception.recovery()
        );

        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void shouldPauseOnAuthenticationFailure() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenThrow(
                failure(
                        ExecutionFailureKind.AUTHENTICATION,
                        401,
                        null
                )
        );

        var exception = assertThrows(
                JobProcessingDeferredException.class,
                () -> processor.process(validMessage)
        );

        assertEquals(
                Recovery.PAUSE_INTAKE,
                exception.recovery()
        );
    }

    @Test
    void shouldWaitForAnotherActiveClaim() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenThrow(
                failure(
                        ExecutionFailureKind.ACTIVE_CLAIM,
                        409,
                        "urn:taskmesh:problem:execution-active"
                )
        );

        var exception = assertThrows(
                JobProcessingDeferredException.class,
                () -> processor.process(validMessage)
        );

        assertEquals(
                Recovery.WAIT_FOR_ACTIVE_CLAIM,
                exception.recovery()
        );

        verify(executionApi, never()).complete(
                any(), any(), any()
        );
    }

    @Test
    void shouldReconcileStaleCompletion() {
        startRunning();

        when(executionApi.complete(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID
        )).thenThrow(
                failure(
                        ExecutionFailureKind.STALE_EXECUTION,
                        409,
                        "urn:taskmesh:problem:execution-stale"
                )
        );

        var exception = assertThrows(
                JobProcessingDeferredException.class,
                () -> processor.process(validMessage)
        );

        assertEquals(
                Recovery.RECONCILE_START,
                exception.recovery()
        );

        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    void shouldQuarantineSubmissionMismatch() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenThrow(
                failure(
                        ExecutionFailureKind.QUARANTINE,
                        409,
                        "urn:taskmesh:problem:submission-mismatch"
                )
        );

        assertDoesNotThrow(
                () -> processor.process(validMessage)
        );

        verify(deadLetterPublisher).publish(
                same(validMessage),
                eq("START_SUBMISSION_MISMATCH")
        );
    }

    @Test
    void shouldQuarantineOutcomeConflict() {
        startRunning();

        when(executionApi.complete(
                JOB_ID,
                EVENT_ID,
                EXECUTION_ID
        )).thenThrow(
                failure(
                        ExecutionFailureKind.OUTCOME_CONFLICT,
                        409,
                        "urn:taskmesh:problem:outcome-conflict"
                )
        );

        assertDoesNotThrow(
                () -> processor.process(validMessage)
        );

        verify(deadLetterPublisher).publish(
                same(validMessage),
                eq("COMPLETE_OUTCOME_CONFLICT")
        );
    }

    @Test
    void shouldReconcileExpiredExecutionLease() {
        when(executionApi.start(
                eq(JOB_ID),
                eq(EVENT_ID),
                any(UUID.class)
        )).thenReturn(
                new ExecutionApiClient.StartResult(
                        JOB_ID,
                        ExecutionApiClient.JobStatus.RUNNING,
                        EXECUTION_ID,
                        NOW.minusSeconds(1),
                        null
                )
        );

        var exception = assertThrows(
                JobProcessingDeferredException.class,
                () -> processor.process(validMessage)
        );

        assertEquals(
                Recovery.RECONCILE_START,
                exception.recovery()
        );

        verify(executionApi, never()).complete(
                any(), any(), any()
        );
    }
}