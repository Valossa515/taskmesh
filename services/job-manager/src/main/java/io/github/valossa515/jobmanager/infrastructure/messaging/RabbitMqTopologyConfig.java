package io.github.valossa515.jobmanager.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RabbitMqTopologyConfig {

    public static final String JOBS_EXCHANGE = "taskmesh.jobs";
    public static final String PENDING_QUEUE = "taskmesh.jobs.pending";
    public static final String JOB_SUBMITTED_KEY = "job.submitted";

    @Bean
    public ApplicationRunner initializeRabbitMqTopology(
            AmqpAdmin amqpAdmin
    ) {
        return args -> amqpAdmin.initialize();
    }

    @Bean
    public TopicExchange jobsExchange() {
        return new TopicExchange(JOBS_EXCHANGE, true, false);
    }

    @Bean
    public Queue pendingJobQueue() {
        return new Queue(PENDING_QUEUE, true, false, false);
    }

    @Bean
    public Binding jobSubmittedBinding(Queue pendingJobQueue, TopicExchange jobsExchange) {

        return BindingBuilder.bind(pendingJobQueue)
                .to(jobsExchange)
                .with(JOB_SUBMITTED_KEY);
    }
}
