package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jsav.chat.PipelineIntegrationTest;
import com.jsav.chat.WorkerCrashes.Point;
import com.jsav.chat.outbox.OutboxEvent;
import com.jsav.chat.outbox.OutboxPublisher;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * El worker (ADR 0013) contra Postgres y RabbitMQ reales: una notificación por (mensaje, persona)
 * aunque el mismo evento llegue más de una vez o el worker caiga a mitad de la entrega.
 */
class MentionWorkerIntegrationTest extends PipelineIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);

  /** Lleva a la cola todo lo que la outbox tenga pendiente. */
  private void publishPending() {
    OutboxPublisher publisher = new OutboxPublisher(outbox, realTransport, tx, outboxProps);
    while (publisher.drainOnce() > 0) {
      // otra vuelta
    }
  }

  /** Los eventos de ese mensaje tal como están en la outbox. */
  private List<OutboxEvent> eventsOf(long messageId) {
    return jdbc.sql(
            """
            select id, event_type, payload::text as payload from outbox
            where (payload->>'messageId')::bigint = :id order by id
            """)
        .param("id", messageId)
        .query(
            (rs, row) ->
                new OutboxEvent(
                    rs.getLong("id"), rs.getString("event_type"), rs.getString("payload")))
        .list();
  }

  @Test
  void aMention_endsUpAsExactlyOneNotification() {
    World w = newWorld(2);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    double notifiedBefore = counter(MentionWorker.NOTIFIED);

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId()));
    assertThat(counter(MentionWorker.NOTIFIED) - notifiedBefore).isEqualTo(1);
  }

  @Test
  void theSameEventDeliveredTwice_isOneNotification_andTheSecondIsDiscarded() {
    World w = newWorld(1);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    // El publicador pudo haber republicado: el mismo evento otra vez en la cola.
    realTransport.publish(eventsOf(messageId));
    double duplicatesBefore = counter(MentionWorker.DUPLICATES);

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(counter(MentionWorker.DUPLICATES) - duplicatesBefore).isEqualTo(1));
    assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId());
  }

  @Test
  void aCrashBeforeProcessing_isRetriedAndNotifiesOnce() {
    World w = newWorld(1);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    crashes.crashNext(Point.BEFORE_PROCESSING, 1);

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId()));
    assertThat(crashes.crashedBefore()).isEqualTo(1);
  }

  @Test
  void aCrashAfterSavingButBeforeTheAck_isRedelivered_andDeduplicated() {
    World w = newWorld(1);
    long messageId = send(w.owner(), w.channelId(), "hola " + w.mentioning(w.members().get(0)));
    publishPending();
    // La notificación ya está guardada cuando el worker «muere»: RabbitMQ entrega el evento otra
    // vez y el segundo intento la encuentra hecha. Sin deduplicar, serían dos.
    crashes.crashNext(Point.AFTER_PROCESSING, 1);
    double duplicatesBefore = counter(MentionWorker.DUPLICATES);

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(counter(MentionWorker.DUPLICATES) - duplicatesBefore).isEqualTo(1));
    assertThat(crashes.crashedAfter()).isEqualTo(1);
    assertThat(notifiedFor(messageId)).containsExactly(w.members().get(0).getId());
  }

  @Test
  void everyoneMentionedGetsOne_andNobodyElseDoes() {
    World w = newWorld(3);
    var a = w.members().get(0);
    var b = w.members().get(1);
    long messageId = send(w.owner(), w.channelId(), "ey " + w.mentioning(a, b));
    publishPending();

    startWorker();

    await()
        .atMost(WAIT)
        .untilAsserted(
            () -> assertThat(notifiedFor(messageId)).containsExactly(a.getId(), b.getId()));
    assertThat(notificationCount()).isEqualTo(2);
  }
}
