package com.jsav.chat.outbox;

import java.util.Collection;
import java.util.List;
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

  /**
   * Toma hasta {@code limit} eventos pendientes y los <b>bloquea</b> hasta que termine la
   * transacción en curso. {@code SKIP LOCKED} hace que dos publicadores (uno por instancia) nunca
   * se pisen: el que llega segundo se salta lo que el primero tiene tomado en vez de esperarlo o de
   * llevarse lo mismo.
   *
   * <p>Se busca por {@code published_at IS NULL} y no por «el id siguiente al último visto»: el id
   * se asigna antes del commit, así que una fila con id menor puede confirmar después de otra con
   * id mayor, y un cursor por id se la saltaría para siempre.
   */
  public List<OutboxEvent> lockPending(int limit) {
    return jdbc.sql(
            """
            select id, event_type, payload::text as payload
            from outbox
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new OutboxEvent(
                    rs.getLong("id"), rs.getString("event_type"), rs.getString("payload")))
        .list();
  }

  /** Marca como publicados los eventos que el broker confirmó. */
  public void markPublished(Collection<Long> ids) {
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql("update outbox set published_at = now() where id in (:ids)")
        .param("ids", ids)
        .update();
  }
}
