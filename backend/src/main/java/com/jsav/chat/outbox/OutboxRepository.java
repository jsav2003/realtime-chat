package com.jsav.chat.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe en la tabla {@code outbox} (ADR 0004, ADR 0012). {@link #add} debe llamarse <b>dentro de
 * la transacción</b> del dato que origina el evento: es lo que garantiza que el evento existe si y
 * solo si el dato se confirmó. Si se escribiera después del commit, un fallo entre los dos pasos
 * dejaría un dato sin evento, que es justo lo que la outbox existe para evitar.
 */
@Repository
public class OutboxRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public OutboxRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /** Agrega un evento pendiente de publicar. El {@code payload} se guarda como JSON. */
  public void add(String eventType, Object payload) {
    jdbc.sql("insert into outbox (event_type, payload) values (:type, cast(:payload as jsonb))")
        .param("type", eventType)
        .param("payload", json.writeValueAsString(payload))
        .update();
  }
}
