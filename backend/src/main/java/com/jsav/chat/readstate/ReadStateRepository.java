package com.jsav.chat.readstate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code read_state} con SQL directo, como el resto del camino del {@code seq}. Toda
 * consulta está atada a {@code user_id}: nadie lee ni mueve el cursor de otra persona.
 */
@Repository
public class ReadStateRepository {

  private final JdbcClient jdbc;

  public ReadStateRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Avanza el cursor de lectura de {@code userId} en el canal hasta {@code seq}, de una sola
   * sentencia y sin lecturas previas:
   *
   * <ul>
   *   <li><b>Acotado</b> ({@code least}): nunca pasa del {@code last_seq} del canal, así que marcar
   *       un número enorme no esconde los mensajes que lleguen después.
   *   <li><b>Monótono</b> ({@code greatest}): nunca retrocede. Dos pestañas, o dos respuestas que
   *       llegan al revés, no pueden dejar un valor menor que el que ya estaba.
   * </ul>
   *
   * <p>El {@code SELECT} sobre {@code channels} no bloquea la fila: un envío concurrente al canal
   * (que sí la bloquea para reservar el {@code seq}) no espera a esto ni al revés.
   */
  public void advance(long userId, long channelId, long seq) {
    jdbc.sql(
            """
            insert into read_state (user_id, channel_id, last_read_seq)
            select :userId, c.id, least(:seq, c.last_seq) from channels c where c.id = :channelId
            on conflict (user_id, channel_id) do update
              set last_read_seq = greatest(read_state.last_read_seq, excluded.last_read_seq)
            """)
        .param("userId", userId)
        .param("channelId", channelId)
        .param("seq", seq)
        .update();
  }
}
