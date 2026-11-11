
package io.github.valossa515.jobmanager.infrastructure.config;

import io.github.valossa515.jobmanager.application.job.execution.WorkerInstanceRegistrationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "taskmesh.security.worker-instance-registration",
        name = "enabled",
        havingValue = "true"
)
public class WorkerInstanceTokenConfiguration {

    @Bean
    SecretKey workerInstanceSigningKey(
            @Value(
                    "${taskmesh.security.worker-instance-registration.signing-key-base64:}"
            )
            String base64Key
    ) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Worker JWT signing key must be valid Base64",
                    exception
            );
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "Worker JWT signing key must contain at least 32 bytes"
            );
        }

        return new SecretKeySpec(
                keyBytes,
                "HmacSHA256"
        );
    }

    @Bean
    JwtEncoder workerInstanceJwtEncoder(
            SecretKey workerInstanceSigningKey
    ) {
        return NimbusJwtEncoder
                .withSecretKey(workerInstanceSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder workerInstanceJwtDecoder(
            SecretKey workerInstanceSigningKey
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(workerInstanceSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            if (jwt.getAudience().contains(
                    WorkerInstanceRegistrationService.AUDIENCE
            )) {
                return OAuth2TokenValidatorResult.success();
            }

            return invalid(
                    "Invalid worker token audience"
            );
        };

        OAuth2TokenValidator<Jwt> identityValidator = jwt -> {
            String workerId = jwt.getSubject();

            if (!"worker-java".equals(workerId)
                    && !"worker-dotnet".equals(workerId)) {
                return invalid(
                        "Invalid worker identity"
                );
            }

            String instanceId =
                    jwt.getClaimAsString("worker_instance_id");

            if (instanceId == null || instanceId.isBlank()) {
                return invalid(
                        "Worker instance identity is missing"
                );
            }

            try {
                UUID parsed = UUID.fromString(instanceId);

                if (!parsed.toString().equals(instanceId)) {
                    return invalid(
                            "Worker instance identity is invalid"
                    );
                }

            } catch (IllegalArgumentException exception) {
                return invalid(
                        "Worker instance identity must be a UUID"
                );
            }

            return OAuth2TokenValidatorResult.success();
        };

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(
                                WorkerInstanceRegistrationService.ISSUER
                        ),
                        audienceValidator,
                        identityValidator
                )
        );

        return decoder;
    }

    private static OAuth2TokenValidatorResult invalid(
            String message
    ) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                        "invalid_token",
                        message,
                        null
                )
        );
    }
}