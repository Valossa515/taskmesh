package io.github.valossa515.jobmanager.job.create;
import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.spring_courier.core.interfaces.ICommand;

public record CreateJobCommand(
        String type,
        String payload) implements ICommand<Job>{
}
