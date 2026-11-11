package io.github.valossa515.workerjava;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class WorkerJavaApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RabbitProperties rabbit;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private SimpleRabbitListenerContainerFactory listenerFactory;

    @Test
    void startsAsAnIndependentNonWebApplicationWithoutDatabaseAccess() {
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("worker-java");
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                .isEqualTo("none");
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }

    @Test
    void doesNotDeclareTopologyOrRegisterConsumers() {
        assertThat(context.getBeansOfType(AmqpAdmin.class)).isEmpty();
        assertThat(context.getBeansOfType(Declarable.class)).isEmpty();
        assertThat(context.getBean(RabbitListenerEndpointRegistry.class)
                .getListenerContainers()).isEmpty();
        assertThat(rabbit.getListener().getDirect().isAutoStartup()).isFalse();
    }

    @Test
    void createsStoppedContainersWithManualAcknowledgements() {
        var container = listenerFactory.createListenerContainer();
        try {
            assertThat(container.isAutoStartup()).isFalse();
            assertThat(container.isRunning()).isFalse();
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        } finally {
            container.destroy();
        }
    }

    @Test
    void boundsIntakeAndDisablesAutomaticRetryAndRequeue() {
        var listener = rabbit.getListener().getSimple();
        assertThat(listener.getPrefetch()).isEqualTo(1);
        assertThat(listener.getConcurrency()).isEqualTo(1);
        assertThat(listener.getMaxConcurrency()).isEqualTo(1);
        assertThat(listener.getDefaultRequeueRejected()).isFalse();
        assertThat(listener.getRetry().isEnabled()).isFalse();
        assertThat(rabbit.getTemplate().getRetry().isEnabled()).isFalse();
    }

    @Test
    void configuresConfirmedMandatoryPublicationsForFutureQuarantine() {
        assertThat(connectionFactory.isPublisherConfirms()).isTrue();
        assertThat(connectionFactory.isPublisherReturns()).isTrue();
        assertThat(rabbit.getTemplate().getMandatory()).isTrue();
    }
}
