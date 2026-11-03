package com.jsav.chat.attachment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
