package com.jsav.chat.channel;

public enum ChannelKind {
  TEXT,
  PRIVATE,
  DM;

  /** Valor guardado en la columna {@code kind} (minúsculas, como el CHECK). */
  public String dbValue() {
    return name().toLowerCase();
  }

  public static ChannelKind fromDb(String value) {
    return valueOf(value.toUpperCase());
  }
}
