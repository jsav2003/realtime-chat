package com.jsav.chat.harness;

/** Una de las dos instancias del backend del cluster de docker compose. */
public enum Instance {
  A("backend-a"),
  B("backend-b");

  /** Nombre del servicio en docker-compose.yml, para reiniciarlo. */
  public final String service;

  Instance(String service) {
    this.service = service;
  }

  public Instance other() {
    return this == A ? B : A;
  }
}
