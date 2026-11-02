package com.jsav.chat.notification;

import java.time.Instant;
import java.util.List;

public final class NotificationDtos {

  private NotificationDtos() {}

  /**
   * Una mención: quién te mencionó, dónde y el inicio del mensaje. {@code serverId} es nulo si el
   * canal no es de un servidor (mensajes directos). {@code snippet} es de una sola línea y de hasta
   * {@value NotificationService#SNIPPET_LENGTH} caracteres: la lista no muestra el mensaje entero.
   */
  public record NotificationItem(
      long id,
      long messageId,
      long channelId,
      String channelName,
      Long serverId,
      long seq,
      String authorName,
      String snippet,
      Instant createdAt,
      boolean read) {}

  /**
   * {@code unreadCount} cuenta todas las no leídas que puedes ver, aunque {@code items} traiga solo
   * las últimas. Ni una ni otra incluyen las de canales que ya no puedes ver.
   */
  public record NotificationList(long unreadCount, List<NotificationItem> items) {}
}
