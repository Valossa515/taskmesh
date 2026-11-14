
package io.github.valossa515.workerjava.messaging;

import com.rabbitmq.client.Channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Phase;
import static io.github.valossa515.workerjava.messaging
        .JobProcessingDeferredException.Recovery;

class JobMessageListenerTest {

    private JobDeliveryProcessor processor;
    private Channel channel;
    private JobMessageListener listener;

    @BeforeEach
    void setUp() {
        processor = mock(JobDeliveryProcessor.class);
        channel = mock(Channel.class);
        listener = new JobMessageListener(processor);
    }

    private Message message(long deliveryTag) {
        MessageProperties properties =
                new MessageProperties();

        properties.setDeliveryTag(deliveryTag);

        return new Message(
                new byte[] {1, 2, 3},
                properties
        );
    }

    @Test
    void shouldAckOnlyAfterSuccessfulProcessing()
            throws Exception {
        Message delivery = message(7);

        listener.onMessage(delivery, channel);

        var order = inOrder(processor, channel);

        order.verify(processor).process(same(delivery));
        order.verify(channel).basicAck(7L, false);

        assertFalse(listener.isPaused());
    }

    @Test
    void shouldKeepDeliveryUnackedOnDeferredFailure()
            throws Exception {
        Message delivery = message(8);

        doThrow(new JobProcessingDeferredException(
                Recovery.PAUSE_INTAKE,
                Phase.START,
                null
        )).when(processor).process(delivery);

        listener.onMessage(delivery, channel);

        assertTrue(listener.isPaused());

        verify(channel, never()).basicAck(
                anyLong(),
                anyBoolean()
        );

        verifyNoInteractions(channel);
    }

    @Test
    void shouldNotAckWhenQuarantinePublicationFails()
            throws Exception {
        Message delivery = message(9);

        doThrow(new QuarantinePublicationException(
                QuarantinePublicationException
                        .Failure.TIMEOUT,
                "Publisher confirmation timed out"
        )).when(processor).process(delivery);

        listener.onMessage(delivery, channel);

        assertTrue(listener.isPaused());
        verifyNoInteractions(channel);
    }

    @Test
    void shouldNotProcessMoreMessagesAfterPause()
            throws Exception {
        Message first = message(10);
        Message second = message(11);

        doThrow(new JobProcessingDeferredException(
                Recovery.PAUSE_INTAKE,
                Phase.COMPLETE,
                null
        )).when(processor).process(first);

        listener.onMessage(first, channel);
        listener.onMessage(second, channel);

        assertTrue(listener.isPaused());

        verify(processor).process(first);
        verify(processor, never()).process(second);

        verifyNoInteractions(channel);
    }

    @Test
    void shouldPauseOnUnexpectedProcessingError()
            throws Exception {
        Message delivery = message(12);

        doThrow(new IllegalStateException(
                "Unexpected processing failure"
        )).when(processor).process(delivery);

        listener.onMessage(delivery, channel);

        assertTrue(listener.isPaused());
        verifyNoInteractions(channel);
    }

    @Test
    void shouldPauseWhenBrokerAckFails()
            throws Exception {
        Message delivery = message(13);

        doThrow(new IOException(
                "Connection lost during ACK"
        )).when(channel).basicAck(13L, false);

        listener.onMessage(delivery, channel);

        verify(processor).process(delivery);

        verify(channel).basicAck(13L, false);

        assertTrue(listener.isPaused());
    }

    @Test
    void shouldNotAckInvalidDeliveryTag()
            throws Exception {
        Message delivery = message(0);

        listener.onMessage(delivery, channel);

        assertTrue(listener.isPaused());

        verifyNoInteractions(processor);
        verifyNoInteractions(channel);
    }
}