package com.jsav.chat.mention;

/**
 * Evento de la outbox: {@code userId} fue mencionado en el mensaje {@code messageId}. El worker
 * deduplica por el par {@code (messageId, userId)} (ADR 0013), así que un evento puede llegar más
 * de una vez sin que la persona reciba dos notificaciones.
 */
public record MentionEvent(long messageId, long channelId, long authorId, long userId) {

  /** El {@code event_type} con el que se guarda en la outbox. */
  public static final String TYPE = "mention";
}
