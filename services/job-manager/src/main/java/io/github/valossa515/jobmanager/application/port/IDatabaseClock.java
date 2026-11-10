package io.github.valossa515.jobmanager.application.port;

import java.time.Instant;

public interface IDatabaseClock {
    Instant now();
}
