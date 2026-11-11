package io.github.valossa515.workerjava;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.rabbitmq.host=broker.invalid",
        "spring.rabbitmq.port=5679",
        "spring.rabbitmq.username=worker-test",
        "spring.rabbitmq.password=test-only-password",
        "spring.rabbitmq.virtual-host=/workers"
})
class RabbitConnectionConfigurationTests {

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Test
    void acceptsExternalConnectionSettingsWithoutContactingTheBroker() {
        assertThat(connectionFactory.getHost()).isEqualTo("broker.invalid");
        assertThat(connectionFactory.getPort()).isEqualTo(5679);
        assertThat(connectionFactory.getUsername()).isEqualTo("worker-test");
        assertThat(connectionFactory.getVirtualHost()).isEqualTo("/workers");
    }
}
