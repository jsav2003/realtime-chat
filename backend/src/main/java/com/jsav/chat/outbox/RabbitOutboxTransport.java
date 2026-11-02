package com.jsav.chat.outbox;

import com.jsav.chat.config.OutboxProperties;
import com.jsav.chat.outbox.ConfirmedPublisher.Outgoing;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;

/**
 * Lleva los eventos de la outbox al exchange {@value OutboxTopology#EXCHANGE}, con el tipo del
 * evento como clave de ruteo. Un evento cuenta como publicado solo si {@link ConfirmedPublisher} lo
 * vio confirmado y ruteado (ADR 0012).
 */
@Component
public class RabbitOutboxTransport implements OutboxTransport {

  private final ConfirmedPublisher publisher;
  private final OutboxProperties props;

  public RabbitOutboxTransport(ConfirmedPublisher publisher, OutboxProperties props) {
    this.publisher = publisher;
    this.props = props;
  }

  @Override
  public Set<Long> publish(List<OutboxEvent> events) {
    List<Outgoing> outgoing = events.stream().map(e -> new Outgoing(e.type(), message(e))).toList();
    return publisher.publish(OutboxTopology.EXCHANGE, outgoing, props.confirmTimeout()).stream()
        .map(index -> events.get(index).id())
        .collect(Collectors.toSet());
  }

  private static Message message(OutboxEvent event) {
    return MessageBuilder.withBody(event.payload().getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setContentEncoding(StandardCharsets.UTF_8.name())
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
        .setMessageId(Long.toString(event.id()))
        .build();
  }
}
