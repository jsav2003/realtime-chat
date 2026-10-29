package com.jsav.chat.typing;

/** Lo que reciben los suscriptores de {@code /topic/channel.{id}.typing}. No se guarda. */
public record TypingEvent(Long userId, String displayName) {}
