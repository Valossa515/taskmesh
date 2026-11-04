package io.github.valossa515.jobmanager.job.create;

import io.github.valossa515.jobmanager.application.port.IJobRepository;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.spring_courier.core.interfaces.CommandHandler;
import org.springframework.stereotype.Service;

@Service
public class CreateJobHandler implements CommandHandler<CreateJobCommand, Job> {
    private final IJobRepository jobRepository;

    public CreateJobHandler(IJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public Job handle(CreateJobCommand command) {
        Job job = Job.create(
                command.type(),
                command.payload()
        );

        return jobRepository.save(job);
    }
}
