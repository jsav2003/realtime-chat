package com.jsav.chat.notification;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.notification.NotificationDtos.NotificationItem;
import com.jsav.chat.notification.NotificationDtos.NotificationList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que cada persona ve de sus notificaciones (ADR 0011). Una notificación guarda dónde fue la
 * mención, no lo que su dueño puede ver hoy: alguien puede ser expulsado del servidor, sacado de un
 * canal privado o dejar de ser admin <b>después</b> de que lo mencionaran. Por eso la lista se
 * filtra al leer, con {@link AccessPolicy}, contra el acceso de ahora, y no se borra nada al
 * cambiar un permiso: en cualquiera de esos caminos, la notificación deja de verse sola.
 */
@Service
public class NotificationService {

  static final int SNIPPET_LENGTH = 120;
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  private final NotificationQueries queries;
  private final AccessPolicy policy;

  public NotificationService(NotificationQueries queries, AccessPolicy policy) {
    this.queries = queries;
    this.policy = policy;
  }

  /**
   * Las últimas menciones que esta persona puede ver, la más nueva primero, y cuántas tiene sin
   * leer. Primero se queda con los canales que todavía puede leer, y recién entonces consulta: lo
   * que no ve no llega ni a salir de la base, y la cuenta y la lista salen del mismo conjunto.
   */
  @Transactional(readOnly = true)
  public NotificationList list(Long userId, int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit debe estar entre 1 y " + MAX_LIMIT);
    }
    List<Long> visible =
        queries.channelsOf(userId).stream()
            .filter(channelId -> policy.can(userId, channelId, ChannelAction.READ))
            .toList();
    if (visible.isEmpty()) {
      return new NotificationList(0, List.of());
    }
    long unread = queries.countUnread(userId, visible);
    List<NotificationItem> items =
        queries.findLatest(userId, visible, limit).stream()
            .map(
                r ->
                    new NotificationItem(
                        r.id(),
                        r.messageId(),
                        r.channelId(),
                        r.channelName(),
                        r.serverId(),
                        r.seq(),
                        r.authorName(),
                        snippet(r.body()),
                        r.createdAt().toInstant(),
                        r.read()))
            .toList();
    return new NotificationList(unread, items);
  }

  /** 404 si la notificación no existe o es de otra persona: no se distingue. */
  @Transactional
  public void markRead(Long userId, Long notificationId) {
    if (queries.markRead(userId, notificationId) == 0) {
      throw new NotFoundException("Notificación no encontrada");
    }
  }

  @Transactional
  public void markAllRead(Long userId) {
    queries.markAllRead(userId);
  }

  /** El inicio del mensaje en una sola línea, con «…» si se cortó. */
  static String snippet(String body) {
    String oneLine = body.replaceAll("\\s+", " ").strip();
    return oneLine.length() <= SNIPPET_LENGTH
        ? oneLine
        : oneLine.substring(0, SNIPPET_LENGTH - 1).stripTrailing() + "…";
  }
}
