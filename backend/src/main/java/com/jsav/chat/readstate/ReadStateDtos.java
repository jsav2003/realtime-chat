package com.jsav.chat.readstate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public final class ReadStateDtos {

  private ReadStateDtos() {}

  /** Hasta qué {@code seq} del canal leyó la persona. Pasarse del último seq no hace daño. */
  public record MarkReadRequest(@NotNull @Min(0) Long seq) {}
}
