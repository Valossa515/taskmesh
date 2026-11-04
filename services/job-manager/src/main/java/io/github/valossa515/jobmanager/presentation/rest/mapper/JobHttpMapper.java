package io.github.valossa515.jobmanager.presentation.rest.mapper;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.job.create.CreateJobCommand;
import io.github.valossa515.jobmanager.presentation.rest.dto.CreateJobRequest;
import io.github.valossa515.jobmanager.presentation.rest.dto.CreateJobResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JobHttpMapper {
    private final JsonMapper jsonMapper;

    public JobHttpMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public CreateJobCommand toCommand(CreateJobRequest request) {
        return new CreateJobCommand(
                request.type(),
                jsonMapper.writeValueAsString(request.payload())
        );
    }

    public CreateJobResponse toResponse(Job job) {
        return new CreateJobResponse(
                job.getId(),
                job.getType(),
                job.getStatus(),
                job.getCreatedAt()
        );
    }
}
