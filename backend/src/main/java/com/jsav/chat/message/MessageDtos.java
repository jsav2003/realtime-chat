package com.jsav.chat.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jsav.chat.attachment.AttachmentDtos.AttachmentInfo;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class MessageDtos {

  private MessageDtos() {}

  /**
   * El cliente genera {@code clientMsgId} una vez por mensaje y lo repite en cada reintento: así el
   * servidor reconoce el reintento y no crea otro mensaje.
   *
   * <p>El texto puede ir vacío si el mensaje lleva adjuntos ({@code attachmentIds}, ya subidos y
   * confirmados). Cuántos caben lo decide {@code app.storage.max-per-message}.
   */
  public record SendMessageRequest(
      @NotNull UUID clientMsgId,
      @Size(max = 4000) String body,
      @Size(max = 20) List<@NotNull Long> attachmentIds) {

    public SendMessageRequest {
      body = body == null ? "" : body;
      attachmentIds =
          attachmentIds == null
              ? List.of()
              : Collections.unmodifiableList(new ArrayList<>(attachmentIds));
    }

    /** Un mensaje solo de texto, como antes de los adjuntos. */
    public SendMessageRequest(UUID clientMsgId, String body) {
      this(clientMsgId, body, List.of());
    }

    @JsonIgnore
    @AssertTrue(message = "Escribe un texto o adjunta un archivo")
    boolean isBodyOrAttachments() {
      return !body.isBlank() || !attachmentIds.isEmpty();
    }
  }

  public record MessageResponse(
      Long id,
      Long channelId,
      long seq,
      Long authorId,
      String authorName,
      UUID clientMsgId,
      String body,
      Instant createdAt,
      List<AttachmentInfo> attachments) {

    /** Un mensaje sin adjuntos. */
    public MessageResponse(
        Long id,
        Long channelId,
        long seq,
        Long authorId,
        String authorName,
        UUID clientMsgId,
        String body,
        Instant createdAt) {
      this(id, channelId, seq, authorId, authorName, clientMsgId, body, createdAt, List.of());
    }

    static MessageResponse from(Message m) {
      return from(m, List.of());
    }

    static MessageResponse from(Message m, List<AttachmentInfo> attachments) {
      return new MessageResponse(
          m.id(),
          m.channelId(),
          m.seq(),
          m.authorId(),
          m.authorName(),
          m.clientMsgId(),
          m.body(),
          m.createdAt(),
          attachments);
    }
  }

  /**
   * Una página de mensajes, siempre en orden ascendente de {@code seq}. {@code hasMore} dice si hay
   * más en la dirección pedida: más nuevos con {@code after_seq}, más viejos en los demás casos.
   */
  public record MessagePage(List<MessageResponse> messages, boolean hasMore) {}
}
