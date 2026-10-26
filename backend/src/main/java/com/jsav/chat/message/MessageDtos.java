package com.jsav.chat.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageDtos {

  private MessageDtos() {}

  /**
   * El cliente genera {@code clientMsgId} una vez por mensaje y lo repite en cada reintento: así el
   * servidor reconoce el reintento y no crea otro mensaje.
   */
  public record SendMessageRequest(
      @NotNull UUID clientMsgId, @NotBlank @Size(max = 4000) String body) {}

  public record MessageResponse(
      Long id,
      Long channelId,
      long seq,
      Long authorId,
      String authorName,
      UUID clientMsgId,
      String body,
      Instant createdAt) {

    static MessageResponse from(Message m) {
      return new MessageResponse(
          m.id(),
          m.channelId(),
          m.seq(),
          m.authorId(),
          m.authorName(),
          m.clientMsgId(),
          m.body(),
          m.createdAt());
    }
  }

  /**
   * Una página de mensajes, siempre en orden ascendente de {@code seq}. {@code hasMore} dice si hay
   * más en la dirección pedida: más nuevos con {@code after_seq}, más viejos en los demás casos.
   */
  public record MessagePage(List<MessageResponse> messages, boolean hasMore) {}
}
