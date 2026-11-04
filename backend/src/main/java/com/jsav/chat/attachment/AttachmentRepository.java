package com.jsav.chat.attachment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Acceso a {@code attachments} con SQL directo, igual que {@code MessageRepository}. */
@Repository
public class AttachmentRepository {

  private static final String COLUMNS =
      "id, channel_id, uploader_id, message_id, s3_key, filename, size, content_type, created_at,"
          + " uploaded_at";

  private final JdbcClient jdbc;

  public AttachmentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Crea el adjunto pendiente: sin confirmar la subida y sin mensaje. */
  public Attachment insertPending(
      Long channelId,
      Long uploaderId,
      String s3Key,
      String filename,
      long size,
      String contentType) {
    return jdbc.sql(
            """
            insert into attachments (channel_id, uploader_id, s3_key, filename, size, content_type)
            values (:channelId, :uploaderId, :s3Key, :filename, :size, :contentType)
            returning %s
            """
                .formatted(COLUMNS))
        .param("channelId", channelId)
        .param("uploaderId", uploaderId)
        .param("s3Key", s3Key)
        .param("filename", filename)
        .param("size", size)
        .param("contentType", contentType)
        .query(AttachmentRepository::map)
        .single();
  }

  public Optional<Attachment> findById(Long id) {
    return jdbc.sql("select %s from attachments where id = :id".formatted(COLUMNS))
        .param("id", id)
        .query(AttachmentRepository::map)
        .optional();
  }

  /** Los adjuntos que existen entre {@code ids}; los que no, simplemente no vienen. */
  public List<Attachment> findByIds(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("select %s from attachments where id in (:ids)".formatted(COLUMNS))
        .param("ids", ids)
        .query(AttachmentRepository::map)
        .list();
  }

  /** Los adjuntos de estos mensajes, por orden de creación. */
  public List<Attachment> findByMessageIds(Collection<Long> messageIds) {
    if (messageIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            "select %s from attachments where message_id in (:ids) order by id".formatted(COLUMNS))
        .param("ids", messageIds)
        .query(AttachmentRepository::map)
        .list();
  }

  /**
   * Une los adjuntos al mensaje, una sola vez cada uno. El {@code UPDATE} solo toca los que son del
   * autor, del canal, ya confirmados y todavía libres: si dos mensajes compiten por el mismo
   * adjunto, el segundo ve {@code message_id} lleno y cuenta menos filas. Devuelve cuántas unió;
   * quien llama revierte la transacción si no son todas.
   */
  public int claim(Collection<Long> ids, Long uploaderId, Long channelId, Long messageId) {
    return jdbc.sql(
            """
            update attachments set message_id = :messageId
            where id in (:ids) and uploader_id = :uploaderId and channel_id = :channelId
              and uploaded_at is not null and message_id is null
            """)
        .param("messageId", messageId)
        .param("ids", ids)
        .param("uploaderId", uploaderId)
        .param("channelId", channelId)
        .update();
  }

  /**
   * Bloquea hasta {@code limit} adjuntos que ningún mensaje reclamó y que nacieron antes de {@code
   * cutoff}, los más viejos primero. {@code SKIP LOCKED}: lo que otra transacción tiene tomado
   * (otra instancia limpiando, o un envío reclamando) se salta, igual que en el publicador de la
   * outbox. Llamarlo dentro de una transacción; los locks duran hasta su final.
   */
  public List<Attachment> lockOrphans(Instant cutoff, int limit) {
    return jdbc.sql(
            """
            select %s from attachments
            where message_id is null and created_at < :cutoff
            order by created_at, id
            limit :limit
            for update skip locked
            """
                .formatted(COLUMNS))
        .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
        .param("limit", limit)
        .query(AttachmentRepository::map)
        .list();
  }

  public void deleteAll(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("delete from attachments where id in (:ids)").param("ids", ids).update();
  }

  /** Marca la subida como confirmada. Repetirlo no cambia la hora: devuelve falso si ya estaba. */
  public boolean markUploaded(Long id) {
    return jdbc.sql(
                "update attachments set uploaded_at = now() where id = :id and uploaded_at is null")
            .param("id", id)
            .update()
        > 0;
  }

  public void delete(Long id) {
    jdbc.sql("delete from attachments where id = :id").param("id", id).update();
  }

  static Attachment map(ResultSet rs, int row) throws SQLException {
    OffsetDateTime uploadedAt = rs.getObject("uploaded_at", OffsetDateTime.class);
    return new Attachment(
        rs.getLong("id"),
        rs.getLong("channel_id"),
        rs.getLong("uploader_id"),
        rs.getObject("message_id", Long.class),
        rs.getString("s3_key"),
        rs.getString("filename"),
        rs.getLong("size"),
        rs.getString("content_type"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        uploadedAt == null ? null : uploadedAt.toInstant());
  }
}
