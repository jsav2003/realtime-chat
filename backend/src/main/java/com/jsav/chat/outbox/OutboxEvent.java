package com.jsav.chat.outbox;

/** Un evento pendiente tal como está en la tabla {@code outbox}; {@code payload} es JSON. */
public record OutboxEvent(long id, String type, String payload) {}
