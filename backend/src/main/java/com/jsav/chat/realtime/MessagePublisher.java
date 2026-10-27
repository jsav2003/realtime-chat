package com.jsav.chat.realtime;

import com.jsav.chat.message.MessageCreated;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Avisa por WebSocket de cada mensaje nuevo, y solo después de que la transacción confirmó (ADR
 * 0003). Si el envío se revierte, este método no se llama. Si el servidor cae entre el commit y
 * este aviso, nadie lo recibe en vivo y la resincronización por {@code seq} lo recupera.
 */
@Component
public class MessagePublisher {

  private final SimpMessagingTemplate template;

  public MessagePublisher(SimpMessagingTemplate template) {
    this.template = template;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(MessageCreated event) {
    template.convertAndSend(ChannelTopic.of(event.message().channelId()), event.message());
  }
}
