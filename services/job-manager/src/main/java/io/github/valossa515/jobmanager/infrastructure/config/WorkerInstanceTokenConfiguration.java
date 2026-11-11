
package io.github.valossa515.jobmanager.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "taskmesh.security.worker-instance-registration",
        name = "enabled",
        havingValue = "true"
)
public class WorkerInstanceTokenConfiguration {

    @Bean
    JwtEncoder workerInstanceJwtEncoder(
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

        SecretKey secretKey = new SecretKeySpec(
                keyBytes,
                "HmacSHA256"
        );

        return NimbusJwtEncoder
                .withSecretKey(secretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }
}