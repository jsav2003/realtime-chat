package com.jsav.chat.server;

public enum Role {
  OWNER,
  ADMIN,
  MEMBER;

  /** Valor guardado en la columna {@code role} (minúsculas, como el CHECK). */
  public String dbValue() {
    return name().toLowerCase();
  }

  public static Role fromDb(String value) {
    return valueOf(value.toUpperCase());
  }

  /** Owner y admin administran el servidor. */
  public boolean canAdminister() {
    return this == OWNER || this == ADMIN;
  }
}
