package com.jsav.chat.server;

public enum Role {
  OWNER,
  ADMIN,
  MEMBER;

  /** Valor guardado en la columna {@code role} (minúsculas, como el CHECK). */
  public String dbValue() {
    return name().toLowerCase();
  }
}
