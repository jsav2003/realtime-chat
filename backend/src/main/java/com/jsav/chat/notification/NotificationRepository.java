package com.jsav.chat.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code notifications} con SQL directo, para que la deduplicación se lea tal cual. */
@Repository
public class NotificationRepository {

  private final JdbcClient jdbc;

  public NotificationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Crea la notificación si esa persona todavía no tiene una por ese mensaje. Devuelve {@code true}
   * si la creó y {@code false} si ya existía (un evento repetido).
   *
   * <p>Es una sola sentencia y la decide la restricción {@code UNIQUE (message_id, user_id)}: no
   * hay un «¿ya existe?» seguido de un «créala», que dejaría pasar dos si dos workers procesan el
   * mismo evento a la vez. Por eso procesar un evento es seguro de repetir.
   */
  public boolean insertIfAbsent(long messageId, long userId, long channelId) {
    return jdbc.sql(
                """
                insert into notifications (user_id, message_id, channel_id)
                values (:userId, :messageId, :channelId)
                on conflict (message_id, user_id) do nothing
                """)
            .param("userId", userId)
            .param("messageId", messageId)
            .param("channelId", channelId)
            .update()
        == 1;
  }
}
