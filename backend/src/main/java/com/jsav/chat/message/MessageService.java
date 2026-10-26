package com.jsav.chat.message;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageService {

  /** Resultado de un envío: {@code replayed} si era un reintento de un mensaje ya guardado. */
  public record Sent(MessageResponse message, boolean replayed) {}

  /** El INSERT chocó con un reintento que confirmó antes. Lanzarla revierte el seq reservado. */
  private static final class LostRetryRace extends RuntimeException {
    LostRetryRace() {
      super(null, null, false, false);
    }
  }

  private final MessageRepository messages;
  private final AccessPolicy policy;
  private final TransactionTemplate tx;

  public MessageService(MessageRepository messages, AccessPolicy policy, TransactionTemplate tx) {
    this.messages = messages;
    this.policy = policy;
    this.tx = tx;
  }

  /**
   * Guarda un mensaje con el siguiente {@code seq} del canal (DESIGN.md §3.2). Repetir el envío con
   * el mismo {@code clientMsgId} devuelve el mensaje ya guardado, sin crear otro ni gastar un seq.
   *
   * <p>La transacción va en un {@link TransactionTemplate} y no con {@code @Transactional} para que
   * el {@code catch} quede fuera de ella: cuando se llega ahí, la transacción ya se revirtió.
   */
  public Sent send(Long userId, Long channelId, SendMessageRequest req) {
    try {
      return tx.execute(s -> sendInTransaction(userId, channelId, req));
    } catch (LostRetryRace e) {
      // Otro envío con la misma clave confirmó mientras este esperaba. Este ya se revirtió, con su
      // seq; lo que queda es devolver el mensaje que sí quedó guardado.
      Message saved = messages.findByClientMsgId(userId, req.clientMsgId()).orElseThrow();
      return replay(saved, channelId);
    }
  }

  private Sent sendInTransaction(Long userId, Long channelId, SendMessageRequest req) {
    policy.requireChannel(userId, channelId, ChannelAction.WRITE);

    // 1. El caso común de un reintento: el mensaje ya está guardado.
    Optional<Message> existing = messages.findByClientMsgId(userId, req.clientMsgId());
    if (existing.isPresent()) {
      return replay(existing.get(), channelId);
    }

    // 2. Reservar el seq. Desde aquí, hasta el commit, nadie más escribe en este canal.
    long seq = messages.nextSeq(channelId);

    // 3. Guardar. Vacío = otro reintento con la misma clave entró entre el paso 1 y este.
    Message saved =
        messages
            .insert(channelId, seq, userId, req.clientMsgId(), req.body().strip())
            .orElseThrow(LostRetryRace::new);
    return new Sent(MessageResponse.from(saved), false);
  }

  private static Sent replay(Message saved, Long channelId) {
    if (!saved.channelId().equals(channelId)) {
      throw new ConflictException("Ese clientMsgId ya se usó para un mensaje en otro canal");
    }
    return new Sent(MessageResponse.from(saved), true);
  }
}
