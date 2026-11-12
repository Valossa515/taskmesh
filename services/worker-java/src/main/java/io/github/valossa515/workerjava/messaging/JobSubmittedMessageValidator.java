
package io.github.valossa515.workerjava.messaging;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import static io.github.valossa515.workerjava.messaging
        .InvalidJobMessageException.Reason;

@Component
public class JobSubmittedMessageValidator {

    private static final String SCHEMA_RESOURCE =
            "/contracts/schemas/job-submitted.schema.json";

    private static final String SUPPORTED_TYPE = "NO_OP";

    private final JsonMapper json;
    private final Schema schema;

    public JobSubmittedMessageValidator(JsonMapper json) {
        this.json = Objects.requireNonNull(json);
        this.schema = loadSchema();
    }

    public ValidatedSubmission validate(
            byte[] body,
            String amqpMessageId
    ) {
        JsonNode envelope = parse(body);

        var errors = schema.validate(
                envelope,
                context -> context.executionConfig(
                        config -> config.formatAssertionsEnabled(
                                true
                        )
                )
        );

        if (!errors.isEmpty()) {
            throw invalid(
                    Reason.SCHEMA_VIOLATION,
                    "Message does not satisfy JobSubmitted v1.0"
            );
        }

        JsonNode data = envelope.get("data");

        if (!SUPPORTED_TYPE.equals(
                data.get("jobType").asString()
        )) {
            throw invalid(
                    Reason.UNSUPPORTED_JOB_TYPE,
                    "Unsupported job type"
            );
        }

        UUID eventId = UUID.fromString(
                envelope.get("eventId").asString()
        );

        UUID jobId = UUID.fromString(
                data.get("jobId").asString()
        );

        validateMessageId(amqpMessageId, eventId);

        return new ValidatedSubmission(
                eventId,
                jobId
        );
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid(
                    Reason.MALFORMED_JSON,
                    "Message body is empty"
            );
        }

        String content;

        try {
            content = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT
                    )
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT
                    )
                    .decode(ByteBuffer.wrap(body))
                    .toString();

        } catch (CharacterCodingException exception) {
            throw invalid(
                    Reason.MALFORMED_JSON,
                    "Message is not valid UTF-8"
            );
        }

        try {
            JsonNode node = json.readTree(content);

            if (node == null) {
                throw invalid(
                        Reason.MALFORMED_JSON,
                        "Message is empty JSON"
                );
            }

            return node;

        } catch (InvalidJobMessageException exception) {
            throw exception;

        } catch (Exception exception) {
            throw invalid(
                    Reason.MALFORMED_JSON,
                    "Message contains malformed JSON"
            );
        }
    }

    private void validateMessageId(
            String amqpMessageId,
            UUID expectedEventId
    ) {
        if (amqpMessageId == null
                || amqpMessageId.isBlank()) {
            throw invalid(
                    Reason.MISSING_MESSAGE_ID,
                    "AMQP messageId is required"
            );
        }

        UUID receivedId;

        try {
            receivedId = UUID.fromString(amqpMessageId);

        } catch (IllegalArgumentException exception) {
            throw invalid(
                    Reason.INVALID_MESSAGE_ID,
                    "AMQP messageId is not a UUID"
            );
        }

        // UUID.fromString may accept noncanonical text.
        if (!receivedId.toString()
                .equalsIgnoreCase(amqpMessageId)) {
            throw invalid(
                    Reason.INVALID_MESSAGE_ID,
                    "AMQP messageId must be canonical"
            );
        }

        if (!expectedEventId.equals(receivedId)) {
            throw invalid(
                    Reason.MESSAGE_ID_MISMATCH,
                    "AMQP messageId does not match eventId"
            );
        }
    }

    private Schema loadSchema() {
        try (var input = getClass().getResourceAsStream(
                SCHEMA_RESOURCE
        )) {
            if (input == null) {
                throw new IllegalStateException(
                        "JobSubmitted schema is missing "
                                + "from the application classpath"
                );
            }

            String schemaJson = new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
            );

            SchemaRegistry registry =
                    SchemaRegistry.withDefaultDialect(
                            SpecificationVersion.DRAFT_2020_12
                    );

            return registry.getSchema(
                    schemaJson,
                    InputFormat.JSON
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not load JobSubmitted schema",
                    exception
            );
        }
    }

    private InvalidJobMessageException invalid(
            Reason reason,
            String message
    ) {
        return new InvalidJobMessageException(
                reason,
                message
        );
    }

    public record ValidatedSubmission(
            UUID eventId,
            UUID jobId
    ) {
    }
}