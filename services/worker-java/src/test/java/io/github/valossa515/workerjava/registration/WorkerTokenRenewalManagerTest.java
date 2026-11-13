
package io.github.valossa515.workerjava.registration;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkerTokenRenewalManagerTest {

    private static final Instant NOW =
            Instant.parse("2030-01-01T00:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID INSTANCE_ID =
            UUID.fromString(
                    "11111111-2222-4333-8444-555555555555"
            );

    private WorkerSession session(
            UUID instanceId,
            String token,
            Instant expiresAt
    ) {
        return new WorkerSession(
                "worker-java",
                instanceId,
                token,
                expiresAt
        );
    }

    private WorkerSession oldSession() {
        return session(
                INSTANCE_ID,
                "old-token",
                NOW.plusSeconds(240)
        );
    }

    private WorkerSession renewedSession() {
        return session(
                INSTANCE_ID,
                "new-token",
                NOW.plusSeconds(3600)
        );
    }

    private WorkerSessionManager manager(
            WorkerRegistrationClient client,
            WorkerSession initial
    ) {
        when(client.register()).thenReturn(initial);

        WorkerSessionManager manager =
                new WorkerSessionManager(client, CLOCK);

        manager.registerOnce();

        return manager;
    }

    @Test
    void shouldSkipRefreshWhenTokenIsStillValid() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession original = session(
                INSTANCE_ID,
                "old-token",
                NOW.plusSeconds(3600)
        );

        var manager = manager(client, original);

        assertSame(original, manager.refreshIfNeeded());

        verify(client, never()).refresh(
                any(WorkerSession.class)
        );
    }

    @Test
    void shouldReplaceTokenWithoutChangingIdentity() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession renewed = renewedSession();

        when(client.refresh(any(WorkerSession.class)))
                .thenReturn(renewed);

        var manager = manager(client, oldSession());

        assertSame(renewed, manager.refreshIfNeeded());

        assertEquals(
                INSTANCE_ID,
                manager.requireSession().workerInstanceId()
        );

        assertEquals(
                "new-token",
                manager.tokenForNewClaim()
        );

        verify(client, times(1)).register();
        verify(client, times(1)).refresh(
                any(WorkerSession.class)
        );
    }

    @Test
    void shouldRefreshOnlyOnceAcrossConcurrentCalls()
            throws Exception {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession renewed = renewedSession();

        when(client.refresh(any(WorkerSession.class)))
                .thenReturn(renewed);

        var manager = manager(client, oldSession());

        var tasks = IntStream.range(0, 8)
                .mapToObj(index ->
                        (Callable<WorkerSession>)
                                manager::refreshIfNeeded
                )
                .toList();

        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(tasks);

            for (var result : results) {
                assertSame(renewed, result.get());
            }
        }

        verify(client, times(1)).refresh(
                any(WorkerSession.class)
        );
    }

    @Test
    void shouldKeepOldTokenOnTransientFailure() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        when(client.refresh(any(WorkerSession.class)))
                .thenThrow(new WorkerTokenRenewalException(
                        WorkerTokenRenewalException.Kind.TRANSIENT,
                        "Temporary network failure"
                ));

        WorkerSession original = oldSession();
        var manager = manager(client, original);

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                manager::refreshIfNeeded
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.TRANSIENT,
                exception.kind()
        );

        assertSame(original, manager.requireSession());

        assertEquals(
                "old-token",
                manager.tokenForNewClaim()
        );
    }

    @Test
    void shouldStopNewClaimsWhenSessionLimitIsReached() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        when(client.refresh(any(WorkerSession.class)))
                .thenThrow(new WorkerTokenRenewalException(
                        WorkerTokenRenewalException.Kind.SESSION_EXPIRED,
                        "Session deadline reached"
                ));

        var manager = manager(client, oldSession());

        assertThrows(
                WorkerTokenRenewalException.class,
                manager::refreshIfNeeded
        );

        assertThrows(
                IllegalStateException.class,
                manager::tokenForNewClaim
        );

        // Old JWT may still finish an existing execution.
        assertEquals(
                "old-token",
                manager.tokenForOutcome()
        );
    }

    @Test
    void shouldRejectDifferentInstanceDuringRefresh() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession differentInstance = session(
                UUID.randomUUID(),
                "new-token",
                NOW.plusSeconds(3600)
        );

        when(client.refresh(any(WorkerSession.class)))
                .thenReturn(differentInstance);

        var manager = manager(client, oldSession());

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                manager::refreshIfNeeded
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.PROTOCOL,
                exception.kind()
        );

        assertThrows(
                IllegalStateException.class,
                manager::tokenForNewClaim
        );
    }

    @Test
    void shouldStopWhenRenewalCannotExtendExpiration() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession original = oldSession();

        WorkerSession notExtended = session(
                INSTANCE_ID,
                "new-token",
                original.expiresAt()
        );

        when(client.refresh(any(WorkerSession.class)))
                .thenReturn(notExtended);

        var manager = manager(client, original);

        var exception = assertThrows(
                WorkerTokenRenewalException.class,
                manager::refreshIfNeeded
        );

        assertEquals(
                WorkerTokenRenewalException.Kind.SESSION_EXPIRED,
                exception.kind()
        );

        assertSame(original, manager.requireSession());
    }

    @Test
    void shouldNotRefreshAnAlreadyExpiredToken() {
        WorkerRegistrationClient client =
                mock(WorkerRegistrationClient.class);

        WorkerSession expired = session(
                INSTANCE_ID,
                "old-token",
                NOW.minusSeconds(10)
        );

        var manager = manager(client, expired);

        assertThrows(
                WorkerTokenRenewalException.class,
                manager::refreshIfNeeded
        );

        verify(client, never()).refresh(
                any(WorkerSession.class)
        );

        verify(client, times(1)).register();

        assertThrows(
                IllegalStateException.class,
                manager::tokenForNewClaim
        );
    }
}