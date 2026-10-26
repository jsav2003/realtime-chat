package com.jsav.chat.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code messages} con SQL directo, para que la asignación del {@code seq} se lea tal cual
 * (DESIGN.md §3.2, ADR 0001). Los métodos que escriben deben llamarse dentro de una transacción:
 * {@link #nextSeq} y {@link #insert} van juntos, en la misma.
 */
@Repository
public class MessageRepository {

  /**
   * Columnas de un mensaje más el nombre del autor; las consultas las seleccionan con alias m/u.
   */
  private static final String COLUMNS =
      "m.id, m.channel_id, m.seq, m.author_id, u.display_name, m.client_msg_id, m.body,"
          + " m.created_at";

  private final JdbcClient jdbc;

  public MessageRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reserva el siguiente {@code seq} del canal. El {@code UPDATE} toma el lock de la fila del canal
   * hasta que la transacción termine: un segundo envío al mismo canal espera aquí, y al continuar
   * lee el {@code last_seq} que dejó el primero. Así dos envíos nunca reciben el mismo número.
   *
   * <p>Si la transacción se revierte, el incremento también: no queda hueco.
   */
  public long nextSeq(Long channelId) {
    return jdbc.sql(
            "update channels set last_seq = last_seq + 1 where id = :channelId returning last_seq")
        .param("channelId", channelId)
        .query(Long.class)
        .single();
  }

  /**
   * Guarda el mensaje con el {@code seq} ya reservado. Si el autor ya usó ese {@code client_msg_id}
   * (un reintento que ganó la carrera), no inserta nada y devuelve vacío: quien llama debe revertir
   * la transacción para devolver el {@code seq}.
   */
  public Optional<Message> insert(
      Long channelId, long seq, Long authorId, UUID clientMsgId, String body) {
    return jdbc.sql(
            """
            with m as (
              insert into messages (channel_id, seq, author_id, client_msg_id, body)
              values (:channelId, :seq, :authorId, :clientMsgId, :body)
              on conflict (author_id, client_msg_id) do nothing
              returning *
            )
            select %s from m join users u on u.id = m.author_id
            """
                .formatted(COLUMNS))
        .param("channelId", channelId)
        .param("seq", seq)
        .param("authorId", authorId)
        .param("clientMsgId", clientMsgId)
        .param("body", body)
        .query(MessageRepository::map)
        .optional();
  }

  /** El mensaje que el autor ya envió con esa clave, en cualquier canal. */
  public Optional<Message> findByClientMsgId(Long authorId, UUID clientMsgId) {
    return jdbc.sql(
            """
            select %s from messages m join users u on u.id = m.author_id
            where m.author_id = :authorId and m.client_msg_id = :clientMsgId
            """
                .formatted(COLUMNS))
        .param("authorId", authorId)
        .param("clientMsgId", clientMsgId)
        .query(MessageRepository::map)
        .optional();
  }

  /**
   * Hasta {@code limit} mensajes con {@code seq > afterSeq}, en orden ascendente. Es la
   * resincronización: el cliente pide lo que vino después del último seq que vio.
   */
  public List<Message> findAfter(Long channelId, long afterSeq, int limit) {
    return jdbc.sql(
            """
            select %s from messages m join users u on u.id = m.author_id
            where m.channel_id = :channelId and m.seq > :afterSeq
            order by m.seq
            limit :limit
            """
                .formatted(COLUMNS))
        .param("channelId", channelId)
        .param("afterSeq", afterSeq)
        .param("limit", limit)
        .query(MessageRepository::map)
        .list();
  }

  /**
   * Hasta {@code limit} mensajes con {@code seq < beforeSeq}, los más recientes primero. Es el
   * historial hacia atrás; con {@code Long.MAX_VALUE} da los últimos del canal.
   */
  public List<Message> findBefore(Long channelId, long beforeSeq, int limit) {
    return jdbc.sql(
            """
            select %s from messages m join users u on u.id = m.author_id
            where m.channel_id = :channelId and m.seq < :beforeSeq
            order by m.seq desc
            limit :limit
            """
                .formatted(COLUMNS))
        .param("channelId", channelId)
        .param("beforeSeq", beforeSeq)
        .param("limit", limit)
        .query(MessageRepository::map)
        .list();
  }

  static Message map(ResultSet rs, int row) throws SQLException {
    return new Message(
        rs.getLong("id"),
        rs.getLong("channel_id"),
        rs.getLong("seq"),
        rs.getLong("author_id"),
        rs.getString("display_name"),
        rs.getObject("client_msg_id", UUID.class),
        rs.getString("body"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
