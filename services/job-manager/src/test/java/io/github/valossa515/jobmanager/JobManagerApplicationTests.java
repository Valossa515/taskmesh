package io.github.valossa515.jobmanager;

import io.github.valossa515.jobmanager.support.IntegrationTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(IntegrationTestConfig.class)
class JobManagerApplicationTests {

	@Test
	void contextLoads() {
	}
}
