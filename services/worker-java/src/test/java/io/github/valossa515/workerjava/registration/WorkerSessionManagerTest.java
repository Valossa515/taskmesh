
package io.github.valossa515.workerjava.registration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerSessionManagerTest {

    private static final Instant NOW =
            Instant.parse("2030-01-01T00:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private WorkerSession session(Instant expiresAt) {
        return new WorkerSession(
                "worker-java",
                UUID.randomUUID(),
                "signed-test-token",
                expiresAt
        );
    }

    @Test
    void shouldRegisterOnlyOncePerProcess() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession issued =
                session(NOW.plusSeconds(3600));

        when(client.register()).thenReturn(issued);

        WorkerSessionManager manager =
                new WorkerSessionManager(client, CLOCK);

        WorkerSession first = manager.registerOnce();
        WorkerSession second = manager.registerOnce();

        assertSame(first, second);
        assertEquals(
                first.workerInstanceId(),
                second.workerInstanceId()
        );
        assertEquals(
                "signed-test-token",
                manager.tokenForNewClaim()
        );

        verify(client, times(1)).register();
    }

    @Test
    void shouldStopNewClaimsNearTokenExpiration() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        when(client.register()).thenReturn(
                session(NOW.plusSeconds(30))
        );

        WorkerSessionManager manager =
                new WorkerSessionManager(client, CLOCK);

        manager.registerOnce();

        assertThrows(
                IllegalStateException.class,
                manager::tokenForNewClaim
        );

        assertEquals(
                "signed-test-token",
                manager.tokenForOutcome()
        );

        verify(client, times(1)).register();
    }

    @Test
    void shouldNotRegisterNewIdentityAfterExpiration() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession expired =
                session(NOW.minusSeconds(10));

        when(client.register()).thenReturn(expired);

        WorkerSessionManager manager =
                new WorkerSessionManager(client, CLOCK);

        manager.registerOnce();

        assertThrows(
                IllegalStateException.class,
                manager::tokenForNewClaim
        );

        assertThrows(
                IllegalStateException.class,
                manager::tokenForOutcome
        );

        assertSame(expired, manager.registerOnce());

        verify(client, times(1)).register();
    }
}