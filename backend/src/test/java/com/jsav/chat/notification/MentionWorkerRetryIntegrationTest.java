package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jsav.chat.PipelineIntegrationTest;
import com.jsav.chat.mention.MentionEvent;
import com.jsav.chat.outbox.OutboxEvent;
import com.jsav.chat.outbox.OutboxPublisher;
import com.jsav.chat.outbox.OutboxTopology;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Qué hace el worker cuando falla (ADR 0013): reintenta con esperas cada vez más largas, manda a la
 * cola de mensajes muertos lo que no tiene arreglo o agotó sus intentos, y no confirma un evento
 * que no pudo dejar a salvo. Las esperas de las pruebas son de 100, 200 y 400 ms (ver {@code
 * src/test/resources/config/application.properties}).
 *
 * <p>Los fallos son de verdad: un trigger en Postgres hace fallar el {@code INSERT} de la
 * notificación las veces que se pida.
 */
class MentionWorkerRetryIntegrationTest extends PipelineIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  @Autowired JdbcTemplate jdbcTemplate;

  @AfterEach
  void stopFailing() {
    jdbcTemplate.execute("drop trigger if exists test_fail_notifications on notifications");
    jdbcTemplate.execute("drop function if exists test_fail_notifications()");
    jdbcTemplate.execute("drop sequence if exists test_fail_attempts");
  }

  /**
   * Hace fallar los próximos {@code times} INSERT en {@code notifications}; los siguientes pasan.
   *
   * <p>Los intentos se cuentan con una secuencia y no con una tabla: cuando el trigger lanza la
   * excepción, Postgres revierte todo lo que la sentencia hizo, incluido un contador guardado en
   * una tabla, y el presupuesto nunca bajaría. {@code nextval} no se revierte.
   */
  private void failNextInserts(int times) {
    jdbcTemplate.execute("drop sequence if exists test_fail_attempts");
    jdbcTemplate.execute("create sequence test_fail_attempts");
    jdbcTemplate.execute(
        """
        create or replace function test_fail_notifications() returns trigger as $$
        begin
          if nextval('test_fail_attempts') <= %d then
            raise exception 'falla simulada de la base';
          end if;
          return new;
        end $$ language plpgsql
        """
            .formatted(times));
    jdbcTemplate.execute("drop trigger if exists test_fail_notifications on notifications");
    jdbcTemplate.execute(
        "create trigger test_fail_notifications before insert on notifications "
            + "for each row execute function test_fail_notifications()");
  }

  private void publishPending() {
    OutboxPublisher publisher = new OutboxPublisher(outbox, realTransport, tx, outboxProps);
    while (publisher.drainOnce() > 0) {
      // otra vuelta
    }
  }

  private long deadLetters() {
    return admin.getQueueInfo(MentionTopology.DEAD_LETTERS).getMessageCount();
  }

  private static Object header(Message message, String name) {
    return message.getMessageProperties().getHeader(name);
  }

  @Test
  void aTransientFailure_isRetriedWithGrowingWaits_andNotifiesOnce() {
    World w = newWorld(1);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    failNextInserts(2);
    double retriedBefore = counter(MentionWorker.RETRIED);
    long startedAt = System.nanoTime();

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId()));
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
    assertThat(counter(MentionWorker.RETRIED) - retriedBefore).isEqualTo(2);
    // El primer reintento espera 100 ms y el segundo, 200: el total no puede ser menor.
    assertThat(elapsedMillis).isGreaterThanOrEqualTo(300);
    assertThat(deadLetters()).isZero();
  }

  @Test
  void ifEveryAttemptFails_theEventEndsInTheDeadLetterQueue_withTheReason() {
    World w = newWorld(1);
    send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    failNextInserts(1000);
    double retriedBefore = counter(MentionWorker.RETRIED);
    double deadBefore = counter(MentionWorker.DEAD);

    startWorker();

    await().atMost(WAIT).untilAsserted(() -> assertThat(deadLetters()).isEqualTo(1));
    Message dead = rabbit.receive(MentionTopology.DEAD_LETTERS, 2000);
    assertThat(dead).isNotNull();
    // 3 esperas = 4 intentos: el cuarto es el último.
    assertThat(header(dead, MentionWorker.ATTEMPT_HEADER)).isEqualTo(4);
    assertThat((String) header(dead, MentionWorker.ERROR_HEADER)).contains("falla simulada");
    assertThat(header(dead, MentionWorker.FAILED_AT_HEADER)).isNotNull();
    assertThat(notificationCount()).isZero();
    assertThat(counter(MentionWorker.RETRIED) - retriedBefore).isEqualTo(3);
    assertThat(counter(MentionWorker.DEAD) - deadBefore).isEqualTo(1);
    // Nada queda dando vueltas: ni en la cola principal ni en las de espera.
    assertThat(admin.getQueueInfo(MentionTopology.QUEUE).getMessageCount()).isZero();
  }

  @Test
  void anUnreadableEvent_goesStraightToTheDeadLetterQueue_withoutWaiting() {
    rabbit.convertAndSend(OutboxTopology.EXCHANGE, MentionEvent.TYPE, "esto no es json");
    double retriedBefore = counter(MentionWorker.RETRIED);

    startWorker();

    await().atMost(WAIT).untilAsserted(() -> assertThat(deadLetters()).isEqualTo(1));
    Message dead = rabbit.receive(MentionTopology.DEAD_LETTERS, 2000);
    assertThat(dead).isNotNull();
    assertThat(header(dead, MentionWorker.ATTEMPT_HEADER)).isEqualTo(1);
    assertThat(counter(MentionWorker.RETRIED) - retriedBefore).isZero();
  }

  @Test
  void anEventForAMessageThatNoLongerExists_isNotRetried() {
    World w = newWorld(1);
    String payload =
        json.writeValueAsString(
            new MentionEvent(
                999_999_999L, w.channelId(), w.owner().getId(), w.members().get(0).getId()));
    realTransport.publish(List.of(new OutboxEvent(1, MentionEvent.TYPE, payload)));
    double retriedBefore = counter(MentionWorker.RETRIED);

    startWorker();

    await().atMost(WAIT).untilAsserted(() -> assertThat(deadLetters()).isEqualTo(1));
    Message dead = rabbit.receive(MentionTopology.DEAD_LETTERS, 2000);
    assertThat(dead).isNotNull();
    assertThat((String) header(dead, MentionWorker.ERROR_HEADER))
        .contains("DataIntegrityViolationException");
    assertThat(counter(MentionWorker.RETRIED) - retriedBefore).isZero();
    assertThat(notificationCount()).isZero();
  }

  @Test
  void ifTheHandOffIsNotConfirmed_theEventIsNotLost() {
    World w = newWorld(1);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    failNextInserts(1);
    double retriedBefore = counter(MentionWorker.RETRIED);
    // Sin la cola de espera, el traspaso del primer fallo vuelve devuelto y no se confirma: el
    // worker no puede dar por entregado el original, que vuelve a la cola y se reintenta.
    admin.deleteQueue(MentionTopology.waitQueueName(Duration.ofMillis(100)));
    try {
      startWorker();

      await()
          .atMost(WAIT)
          .untilAsserted(
              () -> assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId()));
      assertThat(counter(MentionWorker.RETRIED) - retriedBefore).isZero();
      assertThat(deadLetters()).isZero();
    } finally {
      // Vuelve a declarar la cola de espera para las demás pruebas.
      ((RabbitAdmin) admin).initialize();
    }
  }
}
