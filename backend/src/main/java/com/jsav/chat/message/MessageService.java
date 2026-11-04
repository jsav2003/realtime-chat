package com.jsav.chat.message;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.attachment.Attachment;
import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import com.jsav.chat.attachment.AttachmentRepository;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.config.StorageProperties;
import com.jsav.chat.mention.MentionEvent;
import com.jsav.chat.mention.MentionResolver;
import com.jsav.chat.message.MessageDtos.MessagePage;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.outbox.OutboxRepository;
import com.jsav.chat.ratelimit.MessageRateLimiter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final MentionResolver mentions;
  private final OutboxRepository outbox;
  private final AttachmentRepository attachments;
  private final StorageProperties storage;

  public MessageService(
      MessageRepository messages,
      AccessPolicy policy,
      TransactionTemplate tx,
      ApplicationEventPublisher events,
      MessageRateLimiter rateLimiter,
      MentionResolver mentions,
      OutboxRepository outbox,
      AttachmentRepository attachments,
      StorageProperties storage) {
    this.messages = messages;
    this.policy = policy;
    this.tx = tx;
    this.events = events;
    this.rateLimiter = rateLimiter;
    this.mentions = mentions;
    this.outbox = outbox;
    this.attachments = attachments;
    this.storage = storage;
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

    // 3. Los adjuntos se revisan antes de tocar el seq: uno inválido es un 400 o un 409 y no deja
    // huecos en la secuencia. La revisión es para dar un buen error; lo que impide que dos mensajes
    // se queden con el mismo adjunto es el UPDATE condicional del paso 6 (ADR 0014).
    List<Long> attachmentIds = req.attachmentIds();
    requireClaimable(userId, channelId, attachmentIds);

    // 4. A quién se menciona (ADR 0011). Son solo lecturas y va antes de reservar el seq: el lock
    // de la fila del canal se toma recién en el paso siguiente, y esto no lo alarga.
    String body = req.body().strip();
    List<Long> mentioned = mentions.recipients(userId, channelId, body);

    // 5. Reservar el seq. Desde aquí, hasta el commit, nadie más escribe en este canal.
    long seq = messages.nextSeq(channelId);

    // 6. Guardar. Vacío = otro reintento con la misma clave entró entre el paso 1 y este.
    Message saved =
        messages
            .insert(channelId, seq, userId, req.clientMsgId(), body)
            .orElseThrow(LostRetryRace::new);

    // 7. Unir los adjuntos. Si otro mensaje se quedó con alguno entre la revisión y aquí, el UPDATE
    // cuenta menos filas: se revierte todo, también el seq, y el cliente recibe un 409.
    List<AttachmentInfo> infos = List.of();
    if (!attachmentIds.isEmpty()) {
      int claimed = attachments.claim(attachmentIds, userId, channelId, saved.id());
      if (claimed != attachmentIds.size()) {
        throw new ConflictException("Un adjunto ya se usó en otro mensaje");
      }
      infos = attachmentsOf(List.of(saved.id())).getOrDefault(saved.id(), List.of());
    }

    // 8. Un evento por persona mencionada, en esta misma transacción (ADR 0004). El mensaje, su seq
    // y sus eventos se confirman o se revierten juntos: nunca queda un mensaje sin sus menciones
    // ni un evento de un mensaje que no existe. Un publicador los lleva a RabbitMQ después.
    for (Long recipient : mentioned) {
      outbox.add(MentionEvent.TYPE, new MentionEvent(saved.id(), channelId, userId, recipient));
    }
    MessageResponse response = MessageResponse.from(saved, infos);
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
    // Los adjuntos de toda la página en una sola consulta.
    Map<Long, List<AttachmentInfo>> byMessage =
        attachmentsOf(page.stream().map(Message::id).toList());
    return new MessagePage(
        page.stream()
            .map(m -> MessageResponse.from(m, byMessage.getOrDefault(m.id(), List.of())))
            .toList(),
        hasMore);
  }

  private Sent replay(Message saved, Long channelId) {
    if (!saved.channelId().equals(channelId)) {
      throw new ConflictException("Ese clientMsgId ya se usó para un mensaje en otro canal");
    }
    List<AttachmentInfo> infos =
        attachmentsOf(List.of(saved.id())).getOrDefault(saved.id(), List.of());
    return new Sent(MessageResponse.from(saved, infos), true);
  }

  /** Los adjuntos de estos mensajes, agrupados por mensaje y en orden de creación. */
  private Map<Long, List<AttachmentInfo>> attachmentsOf(List<Long> messageIds) {
    Map<Long, List<AttachmentInfo>> byMessage = new LinkedHashMap<>();
    for (Attachment a : attachments.findByMessageIds(messageIds)) {
      byMessage.computeIfAbsent(a.messageId(), id -> new ArrayList<>()).add(AttachmentInfo.from(a));
    }
    return byMessage;
  }

  /**
   * Que cada adjunto exista, sea del autor y de este canal (400 si no, sin distinguir cuál: no se
   * revela si el id es de otro) y esté confirmado y libre (409 si no).
   */
  private void requireClaimable(Long userId, Long channelId, List<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    if (ids.size() > storage.maxPerMessage()) {
      throw new BadRequestException(
          "Un mensaje admite como máximo " + storage.maxPerMessage() + " adjuntos");
    }
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new BadRequestException("Hay adjuntos repetidos");
    }
    List<Attachment> found = attachments.findByIds(ids);
    boolean allMine =
        found.size() == ids.size()
            && found.stream()
                .allMatch(a -> a.uploaderId().equals(userId) && a.channelId().equals(channelId));
    if (!allMine) {
      throw new BadRequestException("Algún adjunto no es válido para este mensaje");
    }
    for (Attachment a : found) {
      if (!a.isUploaded()) {
        throw new ConflictException("Un adjunto todavía no tiene la subida confirmada");
      }
      if (a.isAttached()) {
        throw new ConflictException("Un adjunto ya se usó en otro mensaje");
      }
    }
  }
}
