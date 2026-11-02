package com.jsav.chat.notification;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lo que lee y cambia quien recibe las notificaciones (la lista y marcar leídas). Todo está atado a
 * {@code user_id}: ninguna consulta recibe solo el id de una notificación, así que nadie puede ver
 * ni tocar la de otra persona aunque adivine el número.
 */
@Repository
public class NotificationQueries {

  /** Una notificación con lo que hace falta para mostrarla; {@code body} es el mensaje entero. */
  record Row(
      long id,
      long messageId,
      long channelId,
      String channelName,
      Long serverId,
      long seq,
      String authorName,
      String body,
      OffsetDateTime createdAt,
      boolean read) {}

  private final JdbcClient jdbc;

  public NotificationQueries(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Los canales en los que esta persona tiene alguna notificación. */
  public List<Long> channelsOf(long userId) {
    return jdbc.sql("select distinct channel_id from notifications where user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .list();
  }

  /** Cuántas no leídas tiene en esos canales. */
  public long countUnread(long userId, Collection<Long> channelIds) {
    return jdbc.sql(
            """
            select count(*) from notifications
            where user_id = :userId and read_at is null and channel_id in (:channelIds)
            """)
        .param("userId", userId)
        .param("channelIds", channelIds)
        .query(Long.class)
        .single();
  }

  /** Las últimas {@code limit} de esos canales, la más nueva primero. */
  List<Row> findLatest(long userId, Collection<Long> channelIds, int limit) {
    return jdbc.sql(
            """
            select n.id, n.message_id, n.channel_id, c.name as channel_name, c.server_id,
                   m.seq, u.display_name as author_name, m.body, n.created_at,
                   (n.read_at is not null) as read
            from notifications n
            join messages m on m.id = n.message_id
            join channels c on c.id = n.channel_id
            join users u on u.id = m.author_id
            where n.user_id = :userId and n.channel_id in (:channelIds)
            order by n.id desc
            limit :limit
            """)
        .param("userId", userId)
        .param("channelIds", channelIds)
        .param("limit", limit)
        .query(
            (rs, i) ->
                new Row(
                    rs.getLong("id"),
                    rs.getLong("message_id"),
                    rs.getLong("channel_id"),
                    rs.getString("channel_name"),
                    rs.getObject("server_id", Long.class),
                    rs.getLong("seq"),
                    rs.getString("author_name"),
                    rs.getString("body"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getBoolean("read")))
        .list();
  }

  /**
   * Marca como leída la notificación {@code id} <b>de esta persona</b>. Devuelve cuántas filas
   * encontró: 0 si no existe o es de otra persona, sin distinguir los dos casos. Marcar una que ya
   * estaba leída también devuelve 1 y conserva la hora original.
   */
  public int markRead(long userId, long id) {
    return jdbc.sql(
            """
            update notifications set read_at = coalesce(read_at, now())
            where id = :id and user_id = :userId
            """)
        .param("id", id)
        .param("userId", userId)
        .update();
  }

  /** Marca como leídas todas las de esta persona. */
  public int markAllRead(long userId) {
    return jdbc.sql(
            "update notifications set read_at = now() where user_id = :userId and read_at is null")
        .param("userId", userId)
        .update();
  }
}
