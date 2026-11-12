
package io.github.valossa515.jobmanager.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RabbitMqTopologyConfigTest {

    @Test
    void shouldDeclareDurableDeadLetterQueue() {
        var topology = new RabbitMqTopologyConfig();

        var queue = topology.deadJobQueue();

        assertEquals(
                "taskmesh.jobs.dead",
                queue.getName()
        );
        assertTrue(queue.isDurable());
        assertFalse(queue.isExclusive());
        assertFalse(queue.isAutoDelete());
    }
}