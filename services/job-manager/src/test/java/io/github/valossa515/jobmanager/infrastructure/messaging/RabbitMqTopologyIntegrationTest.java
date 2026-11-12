
package io.github.valossa515.jobmanager.infrastructure.messaging;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;

import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import org.testcontainers.rabbitmq.RabbitMQContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "taskmesh.rabbitmq.initialize-on-startup=true")
@Import({
        IntegrationTestConfig.class,
        RabbitMqTopologyIntegrationTest.RabbitTestConfig.class
})
class RabbitMqTopologyIntegrationTest {

    @Autowired
    private TopicExchange jobsExchange;

    @Autowired
    @Qualifier("pendingJobQueue")
    private Queue pendingJobsQueue;

    @Autowired
    @Qualifier("deadJobQueue")
    private Queue deadJobsQueue;

    @Autowired
    private Binding jobSubmittedBinding;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitMQContainer rabbitMQContainer;

    @Test
    void shouldDeclareExchangeQueueAndBinding() throws Exception {

        // Assert configuration
        assertEquals(
                "taskmesh.jobs",
                jobsExchange.getName()
        );
        assertEquals("topic", jobsExchange.getType());
        assertTrue(jobsExchange.isDurable());

        assertEquals(
                "taskmesh.jobs.pending",
                pendingJobsQueue.getName()
        );
        assertTrue(pendingJobsQueue.isDurable());

        assertEquals(
                "job.submitted",
                jobSubmittedBinding.getRoutingKey()
        );

        // Force a broker connection so RabbitAdmin can declare
        // the configured exchange, queue and binding.
        assertNotNull(
                rabbitAdmin.getQueueInfo(
                        RabbitMqTopologyConfig.PENDING_QUEUE
                )
        );

        // Verify exchange and queue exist on the real broker.
        connectionFactory.createConnection()
                .createChannel(false)
                .exchangeDeclarePassive(
                        RabbitMqTopologyConfig.JOBS_EXCHANGE
                );

        var queueInfo = rabbitAdmin.getQueueInfo(
                RabbitMqTopologyConfig.PENDING_QUEUE
        );

        assertNotNull(queueInfo);

        // Verify the actual binding on RabbitMQ.
        var result = rabbitMQContainer.execInContainer(
                "rabbitmqctl",
                "list_bindings",
                "-p", "/",
                "source_name",
                "destination_name",
                "routing_key"
        );

        assertEquals(0, result.getExitCode());

        String bindings = result.getStdout();

        assertTrue(
                bindings.lines().anyMatch(line -> {
                    String[] fields = line.split("\\t");

                    return fields.length >= 3
                            && fields[0].equals("taskmesh.jobs")
                            && fields[1].equals("taskmesh.jobs.pending")
                            && fields[2].equals("job.submitted");
                }),
                "Expected job.submitted binding on RabbitMQ"
        );

        assertEquals(
                RabbitMqTopologyConfig.DEAD_QUEUE,
                deadJobsQueue.getName()
        );

        assertTrue(deadJobsQueue.isDurable());

        assertNotNull(
                rabbitAdmin.getQueueInfo(
                        RabbitMqTopologyConfig.DEAD_QUEUE
                ),
                "Dead Letter Queue must exist on RabbitMQ"
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitTestConfig {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitMQContainer() {
            return new RabbitMQContainer("rabbitmq:4-management");
        }
    }
}