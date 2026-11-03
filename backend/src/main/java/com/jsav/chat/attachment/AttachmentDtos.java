package com.jsav.chat.attachment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public final class AttachmentDtos {

  private AttachmentDtos() {}

  /** Lo que el cliente declara del archivo antes de subirlo. */
  public record UploadRequest(
      @NotBlank @Size(max = 255) String filename,
      @NotBlank @Size(max = 100) String contentType,
      @Positive long size) {}

  /**
   * La URL para subir con un PUT y los encabezados que hay que repetir tal cual, porque están
   * dentro de la firma.
   */
  public record UploadResponse(
      Long id, String uploadUrl, Map<String, String> headers, Instant expiresAt) {}
}
