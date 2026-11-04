package io.github.valossa515.jobmanager.presentation.rest.response;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.presentation.rest.dto.CreateJobResponse;
import io.github.valossa515.jobmanager.presentation.rest.mapper.JobHttpMapper;
import io.github.valossa515.spring_courier.core.support.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@Component
public class JobResponseMapper {
    private final JobHttpMapper mapper;

    public JobResponseMapper(JobHttpMapper mapper) {
        this.mapper = mapper;
    }

    public ResponseEntity<CreateJobResponse> toCreatedResponse(
            Response<Job> result
    ) {
        if (!result.isSuccess()) {
            int status = result.getStatusCode();

            if (status < 400 || status > 599) {
                status = 500;
            }

            String message = status >= 500
                    ? "Unable to create job"
                    : result.getError();

            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(status),
                    message
            );
        }

        Job job = result.getData();

        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Job creation returned no data"
            );
        }

        URI location = URI.create("/api/v1/jobs/" + job.getId());

        return ResponseEntity
                .created(location)
                .body(mapper.toResponse(job));
    }
}