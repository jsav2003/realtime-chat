package com.jsav.chat.message;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.message.MessageDtos.MessagePage;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.ratelimit.MessageRateLimiter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageService {

  /** Resultado de un envío: {@code replayed} si era un reintento de un mensaje ya guardado. */
  public record Sent(MessageResponse message, boolean replayed) {}

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 100;

  /** El INSERT chocó con un reintento que confirmó antes. Lanzarla revierte el seq reservado. */
  private static final class LostRetryRace extends RuntimeException {
    LostRetryRace() {
      super(null, null, false, false);
    }
  }

  private final MessageRepository messages;
  private final AccessPolicy policy;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;
  private final MessageRateLimiter rateLimiter;

  public MessageService(
      MessageRepository messages,
      AccessPolicy policy,
      TransactionTemplate tx,
      ApplicationEventPublisher events,
      MessageRateLimiter rateLimiter) {
    this.messages = messages;
    this.policy = policy;
    this.tx = tx;
    this.events = events;
    this.rateLimiter = rateLimiter;
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

    // 2. Cobrar el límite. Va después de AccessPolicy (un extraño recibe 404, no 429) y del paso 1
    // (reenviar un mensaje ya guardado no gasta cupo), y antes de reservar el seq: un 429 no deja
    // huecos en la secuencia (ADR 0009).
    rateLimiter.acquire(userId);

    // 3. Reservar el seq. Desde aquí, hasta el commit, nadie más escribe en este canal.
    long seq = messages.nextSeq(channelId);

    // 4. Guardar. Vacío = otro reintento con la misma clave entró entre el paso 1 y este.
    Message saved =
        messages
            .insert(channelId, seq, userId, req.clientMsgId(), req.body().strip())
            .orElseThrow(LostRetryRace::new);
    MessageResponse response = MessageResponse.from(saved);
    // Dentro de la transacción a propósito: el aviso real sale en AFTER_COMMIT (ADR 0003).
    events.publishEvent(new MessageCreated(response));
    return new Sent(response, false);
  }

  /**
   * Paginación por cursor sobre {@code seq} (nunca por fecha ni por offset):
   *
   * <ul>
   *   <li>{@code afterSeq}: lo que vino después, para resincronizar.
   *   <li>{@code beforeSeq}: lo anterior, para cargar historial.
   *   <li>ninguno: los últimos {@code limit}.
   * </ul>
   *
   * Se pide un mensaje de más para saber si hay otra página sin contar filas.
   */
  @Transactional(readOnly = true)
  public MessagePage list(Long userId, Long channelId, Long afterSeq, Long beforeSeq, int limit) {
    policy.requireChannel(userId, channelId, ChannelAction.READ);
    if (afterSeq != null && beforeSeq != null) {
      throw new BadRequestException("Usa after_seq o before_seq, no los dos");
    }
    if ((afterSeq != null && afterSeq < 0) || (beforeSeq != null && beforeSeq < 1)) {
      throw new BadRequestException("after_seq debe ser >= 0 y before_seq >= 1");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit debe estar entre 1 y " + MAX_LIMIT);
    }

    List<Message> rows;
    if (afterSeq != null) {
      rows = messages.findAfter(channelId, afterSeq, limit + 1);
    } else {
      long before = beforeSeq != null ? beforeSeq : Long.MAX_VALUE;
      rows = new ArrayList<>(messages.findBefore(channelId, before, limit + 1));
    }

    boolean hasMore = rows.size() > limit;
    List<Message> page = hasMore ? rows.subList(0, limit) : rows;
    if (afterSeq == null) {
      // findBefore los trae del más nuevo al más viejo; la respuesta va siempre ascendente.
      page = new ArrayList<>(page);
      Collections.reverse(page);
    }
    return new MessagePage(page.stream().map(MessageResponse::from).toList(), hasMore);
  }

  private static Sent replay(Message saved, Long channelId) {
    if (!saved.channelId().equals(channelId)) {
      throw new ConflictException("Ese clientMsgId ya se usó para un mensaje en otro canal");
    }
    return new Sent(MessageResponse.from(saved), true);
  }
}
