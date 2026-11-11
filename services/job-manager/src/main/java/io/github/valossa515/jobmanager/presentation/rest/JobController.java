package io.github.valossa515.jobmanager.presentation.rest;

import io.github.valossa515.jobmanager.domain.model.Job;
import io.github.valossa515.jobmanager.presentation.rest.dto.CreateJobRequest;
import io.github.valossa515.jobmanager.presentation.rest.dto.CreateJobResponse;
import io.github.valossa515.jobmanager.presentation.rest.mapper.JobHttpMapper;
import io.github.valossa515.jobmanager.presentation.rest.response.JobResponseMapper;
import io.github.valossa515.spring_courier.core.Courier;
import io.github.valossa515.spring_courier.core.support.Response;
import io.github.valossa515.jobmanager.application.job.create.JobTypeAdmission;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final Courier courier;
    private final JobHttpMapper mapper;
    private final JobResponseMapper responseMapper;

    public JobController(Courier courier, JobHttpMapper mapper, JobResponseMapper responseMapper) {
        this.courier = courier;
        this.mapper = mapper;
        this.responseMapper = responseMapper;
    }


    @PostMapping
    public ResponseEntity<CreateJobResponse> create(
            @Valid @RequestBody CreateJobRequest request
    ) {
        // Surface admission errors before Courier dispatch.
        JobTypeAdmission.requireSupported(request.type());

        Response<Job> result = courier.send(
                mapper.toCommand(request)
        );

        return responseMapper.toCreatedResponse(result);
    }
}
