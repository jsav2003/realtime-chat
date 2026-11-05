package com.jsav.chat.readstate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class ReadStateDtos {

  private ReadStateDtos() {}

  /** Un canal con lo que su dueño lleva leído: {@code unread = lastSeq - lastReadSeq}. */
  public record ChannelUnread(long channelId, long lastSeq, long lastReadSeq, long unread) {}

  /** Solo los canales que la persona puede leer ahora. */
  public record UnreadList(List<ChannelUnread> channels) {}

  /** Hasta qué {@code seq} del canal leyó la persona. Pasarse del último seq no hace daño. */
  public record MarkReadRequest(@NotNull @Min(0) Long seq) {}
}
