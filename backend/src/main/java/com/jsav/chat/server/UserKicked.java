package com.jsav.chat.server;

/**
 * Un miembro fue expulsado de un servidor. Se publica dentro de la transacción de la expulsión; el
 * corte de sus WebSockets sale en AFTER_COMMIT, igual que los avisos de mensajes (ADR 0003), para
 * no cortar a alguien cuya expulsión luego se revirtió.
 */
public record UserKicked(Long serverId, Long userId) {}
