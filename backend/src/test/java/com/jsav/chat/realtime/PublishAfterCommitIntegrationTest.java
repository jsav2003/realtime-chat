package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.message.MessageCreated;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR 0003: el aviso sale después del commit, y solo si hubo commit. */
class PublishAfterCommitIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration QUIET = Duration.ofSeconds(2);
  private static final Duration WAIT = Duration.ofSeconds(10);

  @Autowired TransactionTemplate tx;
  @Autowired ApplicationEventPublisher events;

  private MessageCreated fakeEvent() {
    return new MessageCreated(
        new MessageResponse(
            -1L,
            general,
            999,
            member.getId(),
            "Member",
            UUID.randomUUID(),
            "no existe",
            Instant.now()));
  }

  @Test
  void aRolledBackTransactionPublishesNothing() {
    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      tx.executeWithoutResult(
          status -> {
            events.publishEvent(fakeEvent());
            status.setRollbackOnly();
          });

      assertThat(listener.nextMessage(QUIET)).isNull();
      // El canal sigue vivo: un envío real sí llega, así que el silencio de arriba no es un fallo.
      send(admin, general, "real");
      assertThat((Integer) JsonPath.read(listener.nextMessage(WAIT), "$.seq")).isEqualTo(1);
    }
  }

  @Test
  void aCommittedTransactionPublishesAfterTheCommit() {
    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      tx.executeWithoutResult(
          status -> {
            events.publishEvent(fakeEvent());
            // Dentro de la transacción todavía no sale nada.
            assertThat(listener.nextMessage(Duration.ofMillis(500))).isNull();
          });

      assertThat((Integer) JsonPath.read(listener.nextMessage(WAIT), "$.seq")).isEqualTo(999);
    }
  }

  @Test
  void aReplayedSendPublishesNothing() {
    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();
      SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "una sola vez");

      messages.send(member.getId(), general, req);
      assertThat((Integer) JsonPath.read(listener.nextMessage(WAIT), "$.seq")).isEqualTo(1);

      assertThat(messages.send(member.getId(), general, req).replayed()).isTrue();

      assertThat(listener.nextMessage(QUIET)).isNull();
      send(admin, general, "siguiente");
      assertThat((Integer) JsonPath.read(listener.nextMessage(WAIT), "$.seq")).isEqualTo(2);
    }
  }

  @Test
  void tenSimultaneousRetriesPublishExactlyOneFrame() throws Exception {
    try (StompTestClient listener = connect(owner)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();
      SendMessageRequest req = new SendMessageRequest(UUID.randomUUID(), "carrera");
      int tasks = 10;
      ExecutorService pool = Executors.newFixedThreadPool(tasks);
      CountDownLatch ready = new CountDownLatch(tasks);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < tasks; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return messages.send(member.getId(), general, req);
                }));
      }
      ready.await();
      go.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
      pool.shutdown();

      assertThat((Integer) JsonPath.read(listener.nextMessage(WAIT), "$.seq")).isEqualTo(1);
      assertThat(listener.nextMessage(QUIET)).isNull();
    }
  }
}
