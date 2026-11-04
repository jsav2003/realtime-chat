package com.jsav.chat.attachment;

import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import com.jsav.chat.attachment.AttachmentDtos.DownloadResponse;
import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AttachmentController {

  private final AttachmentService service;

  public AttachmentController(AttachmentService service) {
    this.service = service;
  }

  /** Paso 1 de un adjunto: pide la URL para subir el archivo directo a S3. */
  @PostMapping("/channels/{channelId}/attachments")
  @ResponseStatus(HttpStatus.CREATED)
  UploadResponse requestUpload(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long channelId,
      @Valid @RequestBody UploadRequest req) {
    return service.requestUpload(Long.valueOf(jwt.getSubject()), channelId, req);
  }

  /** Paso 2: confirma que el archivo ya está en S3. 200 también si ya estaba confirmado. */
  @PostMapping("/attachments/{attachmentId}/complete")
  AttachmentInfo complete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long attachmentId) {
    return service.complete(Long.valueOf(jwt.getSubject()), attachmentId);
  }

  /**
   * Una URL de 60 s para bajar el archivo. Devuelve JSON y no un redirect porque el JWT vive en el
   * navegador y un {@code <img src>} no manda el encabezado {@code Authorization}.
   */
  @GetMapping("/attachments/{attachmentId}")
  DownloadResponse download(@AuthenticationPrincipal Jwt jwt, @PathVariable Long attachmentId) {
    return service.download(Long.valueOf(jwt.getSubject()), attachmentId);
  }
}
