
package io.github.valossa515.jobmanager.application.job.create;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.application.port.IOutboxRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.domain.model.OutboxEvent;
import io.github.valossa515.spring_courier.core.interfaces.CommandHandler;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateJobHandler
        implements CommandHandler<CreateJobCommand, Job> {

    private final IJobRepository jobRepository;
    private final IOutboxRepository outboxRepository;
    private final JobSubmittedEventFactory jobSubmittedEventFactory;

    public CreateJobHandler(
            IJobRepository jobRepository,
            IOutboxRepository outboxRepository,
            JobSubmittedEventFactory jobSubmittedEventFactory
    ) {
        this.jobRepository = jobRepository;
        this.outboxRepository = outboxRepository;
        this.jobSubmittedEventFactory = jobSubmittedEventFactory;
    }

    @Override
    @Transactional
    public Job handle(CreateJobCommand command) {

        // Admission must happen before any database write.
        JobTypeAdmission.requireSupported(command.type());

        Job job = Job.create(
                command.type(),
                command.payload()
        );

        Job savedJob = jobRepository.save(job);

        OutboxEvent event =
                jobSubmittedEventFactory.create(savedJob);

        outboxRepository.save(event);

        return savedJob;
    }
}