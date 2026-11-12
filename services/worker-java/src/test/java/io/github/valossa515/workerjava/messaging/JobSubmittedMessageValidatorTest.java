
package io.github.valossa515.workerjava.messaging;

import io.github.valossa515.workerjava.messaging
        .InvalidJobMessageException.Reason;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JobSubmittedMessageValidatorTest {

    private static final String EVENT_ID =
            "8f7c4c8e-81b5-4b65-a60a-123456789abc";

    private static final String JOB_ID =
            "a8c2f1a0-1b2c-4d5e-8f90-123456789abc";

    private final JsonMapper json =
            JsonMapper.builder().build();

    private JobSubmittedMessageValidator validator;
    private byte[] validMessage;

    @BeforeEach
    void setUp() throws Exception {
        validator = new JobSubmittedMessageValidator(json);

        try (var input = getClass().getResourceAsStream(
                "/contracts/examples/job-submitted.json"
        )) {
            assertNotNull(
                    input,
                    "Shared contract fixture must be packaged"
            );

            validMessage = input.readAllBytes();
        }
    }

    private ObjectNode envelope() throws Exception {
        return (ObjectNode) json.readTree(validMessage);
    }

    private ObjectNode data() throws Exception {
        return (ObjectNode) envelope().get("data");
    }

    private byte[] serialize(ObjectNode node) {
        return json.writeValueAsBytes(node);
    }

    private void assertRejected(
            byte[] message,
            String messageId,
            Reason expected
    ) {
        InvalidJobMessageException exception =
                assertThrows(
                        InvalidJobMessageException.class,
                        () -> validator.validate(
                                message,
                                messageId
                        )
                );

        assertEquals(expected, exception.reason());
    }

    private void assertSchemaViolation(
            ObjectNode message
    ) {
        assertRejected(
                serialize(message),
                EVENT_ID,
                Reason.SCHEMA_VIOLATION
        );
    }

    @Test
    void shouldAcceptSharedContractFixture() {
        var result = validator.validate(
                validMessage,
                EVENT_ID
        );

        assertEquals(
                UUID.fromString(EVENT_ID),
                result.eventId()
        );

        assertEquals(
                UUID.fromString(JOB_ID),
                result.jobId()
        );
    }

    @Test
    void shouldAcceptArbitraryObjectPayload()
            throws Exception {
        ObjectNode event = envelope();

        ObjectNode payload = (ObjectNode) event
                .get("data")
                .get("payload");

        payload.put("action", "NO_OP");
        payload.put("attempt", 1);

        var result = validator.validate(
                serialize(event),
                EVENT_ID
        );

        assertEquals(
                UUID.fromString(JOB_ID),
                result.jobId()
        );
    }

    @Test
    void shouldRejectEmptyMessage() {
        assertRejected(
                new byte[0],
                EVENT_ID,
                Reason.MALFORMED_JSON
        );
    }

    @Test
    void shouldRejectMalformedJson() {
        assertRejected(
                "{invalid-json".getBytes(
                        StandardCharsets.UTF_8
                ),
                EVENT_ID,
                Reason.MALFORMED_JSON
        );
    }

    @Test
    void shouldRejectInvalidUtf8() {
        byte[] invalidUtf8 = {
                (byte) 0xC3,
                (byte) 0x28
        };

        assertRejected(
                invalidUtf8,
                EVENT_ID,
                Reason.MALFORMED_JSON
        );
    }

    @Test
    void shouldRejectInvalidEventUuid()
            throws Exception {
        ObjectNode event = envelope();

        event.put("eventId", "invalid-uuid");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectInvalidJobUuid()
            throws Exception {
        ObjectNode event = envelope();

        ((ObjectNode) event.get("data"))
                .put("jobId", "invalid-uuid");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectInvalidDateTimeFormat()
            throws Exception {
        ObjectNode event = envelope();

        event.put(
                "occurredAt",
                "2026-11-05 14:00:00Z"
        );

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectIncompatibleVersion()
            throws Exception {
        ObjectNode event = envelope();

        event.put("schemaVersion", "2.0");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectUnexpectedEventType()
            throws Exception {
        ObjectNode event = envelope();

        event.put("eventType", "job.completed");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectMissingRequiredFields()
            throws Exception {
        ObjectNode event = envelope();

        event.remove("occurredAt");

        assertSchemaViolation(event);

        event = envelope();

        ((ObjectNode) event.get("data"))
                .remove("jobId");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectUnknownEnvelopeAndDataFields()
            throws Exception {
        ObjectNode event = envelope();

        event.put("debug", true);

        assertSchemaViolation(event);

        event = envelope();

        ((ObjectNode) event.get("data"))
                .put("priority", 10);

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectNonObjectPayloadAndBlankJobType()
            throws Exception {
        ObjectNode event = envelope();

        ((ObjectNode) event.get("data"))
                .put("payload", "not-an-object");

        assertSchemaViolation(event);

        event = envelope();

        ((ObjectNode) event.get("data"))
                .put("jobType", "");

        assertSchemaViolation(event);
    }

    @Test
    void shouldRejectUnsupportedJobType()
            throws Exception {
        ObjectNode event = envelope();

        ((ObjectNode) event.get("data"))
                .put("jobType", "SEND_EMAIL");

        assertRejected(
                serialize(event),
                EVENT_ID,
                Reason.UNSUPPORTED_JOB_TYPE
        );
    }

    @Test
    void shouldRequireAmqpMessageId() {
        assertRejected(
                validMessage,
                null,
                Reason.MISSING_MESSAGE_ID
        );
    }

    @Test
    void shouldRejectInvalidAmqpMessageId() {
        assertRejected(
                validMessage,
                "not-a-uuid",
                Reason.INVALID_MESSAGE_ID
        );
    }

    @Test
    void shouldRejectMessageIdMismatch() {
        assertRejected(
                validMessage,
                UUID.randomUUID().toString(),
                Reason.MESSAGE_ID_MISMATCH
        );
    }

    @Test
    void shouldPreserveOriginalBytesOnValidationError() {
        byte[] original = validMessage.clone();

        assertRejected(
                validMessage,
                "not-a-uuid",
                Reason.INVALID_MESSAGE_ID
        );

        assertArrayEquals(
                original,
                validMessage
        );
    }
}