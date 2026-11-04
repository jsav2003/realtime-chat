package com.jsav.chat.attachment;

import java.time.Instant;

/**
 * Un adjunto. Pendiente mientras {@code uploadedAt} es nulo (no se confirmó la subida); libre
 * mientras {@code messageId} es nulo (ningún mensaje lo reclamó).
 */
public record Attachment(
    Long id,
    Long channelId,
    Long uploaderId,
    Long messageId,
    String s3Key,
    String filename,
    long size,
    String contentType,
    Instant createdAt,
    Instant uploadedAt) {

  public boolean isUploaded() {
    return uploadedAt != null;
  }

  public boolean isAttached() {
    return messageId != null;
  }
}
