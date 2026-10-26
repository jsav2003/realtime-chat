package com.jsav.chat.message;

import java.time.Instant;
import java.util.UUID;

/** Un mensaje guardado, con el nombre de su autor ya resuelto para mostrarlo. */
public record Message(
    Long id,
    Long channelId,
    long seq,
    Long authorId,
    String authorName,
    UUID clientMsgId,
    String body,
    Instant createdAt) {}
