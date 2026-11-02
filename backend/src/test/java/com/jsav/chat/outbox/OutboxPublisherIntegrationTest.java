package com.jsav.chat.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.PipelineIntegrationTest;
import com.jsav.chat.SimulatedCrash;
import com.jsav.chat.mention.MentionEvent;
import com.jsav.chat.notification.MentionTopology;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * El publicador de la outbox (ADR 0012) contra Postgres y RabbitMQ reales: publica solo con
 * confirmación, no pierde un evento si muere a medias y nunca publica dos veces lo mismo cuando hay
 * dos publicadores a la vez.
 */
class OutboxPublisherIntegrationTest extends PipelineIntegrationTest {

  private OutboxPublisher publisher(OutboxTransport transport) {
    return new OutboxPublisher(outbox, transport, tx, outboxProps);
  }

  /**
   * Agrega {@code n} menciones pendientes; el mensaje {@code i} menciona al usuario {@code 100+i}.
   */
  private void addMentions(int n) {
    for (int i = 1; i <= n; i++) {
      outbox.add(MentionEvent.TYPE, new MentionEvent(i, 1, 1, 100L + i));
    }
  }

  private Set<Long> messageIdsIn(List<String> bodies) {
    return bodies.stream().map(b -> field(b, "messageId")).collect(Collectors.toSet());
  }

  @Test
  void publishesThePendingEvents_andMarksThemOnceTheBrokerConfirmed() {
    addMentions(5);

    int published = publisher(realTransport).drainOnce();

    assertThat(published).isEqualTo(5);
    assertThat(pendingEvents()).isZero();
    List<String> bodies = drain(MentionTopology.QUEUE);
    assertThat(bodies).hasSize(5);
    assertThat(bodies.stream().map(b -> field(b, "userId")).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(101L, 102L, 103L, 104L, 105L);
  }

  @Test
  void whenThereIsNothingPending_aRoundDoesNothing() {
    assertThat(publisher(realTransport).drainOnce()).isZero();
    assertThat(drain(MentionTopology.QUEUE)).isEmpty();
  }

  @Test
  void ifTheBrokerConfirmsNothing_theEventsStayPending_andGoOutOnALaterRound() {
    addMentions(3);
    OutboxTransport brokerDown = events -> Set.of();

    assertThat(publisher(brokerDown).drainOnce()).isZero();

    assertThat(pendingEvents()).isEqualTo(3);
    assertThat(drain(MentionTopology.QUEUE)).isEmpty();
    assertThat(publisher(realTransport).drainOnce()).isEqualTo(3);
    assertThat(pendingEvents()).isZero();
    assertThat(drain(MentionTopology.QUEUE)).hasSize(3);
  }

  @Test
  void onlyWhatTheBrokerConfirmedIsMarked() {
    addMentions(4);
    // Confirma solo la mitad: la otra sigue pendiente y saldrá después.
    OutboxTransport half = events -> realTransport.publish(events.subList(0, events.size() / 2));

    assertThat(publisher(half).drainOnce()).isEqualTo(2);

    assertThat(pendingEvents()).isEqualTo(2);
  }

  @Test
  void ifTheTransportBlowsUp_nothingIsMarked() {
    addMentions(3);
    OutboxTransport boom =
        events -> {
          throw new IllegalStateException("el broker no responde");
        };

    assertThatThrownBy(() -> publisher(boom).drainOnce()).isInstanceOf(IllegalStateException.class);

    assertThat(pendingEvents()).isEqualTo(3);
  }

  @Test
  void aCrashBetweenPublishingAndMarking_losesNothing_andRepublishes() {
    addMentions(4);
    // Publica de verdad y «muere» antes de que la transacción marque nada.
    OutboxTransport diesAfterPublishing =
        events -> {
          realTransport.publish(events);
          throw new SimulatedCrash("después de publicar, antes de marcar");
        };

    assertThatThrownBy(() -> publisher(diesAfterPublishing).drainOnce())
        .isInstanceOf(SimulatedCrash.class);
    assertThat(pendingEvents()).as("la transacción se perdió: siguen pendientes").isEqualTo(4);

    assertThat(publisher(realTransport).drainOnce()).isEqualTo(4);

    // Al menos una vez: cada evento está dos veces en la cola. Por eso el worker deduplica.
    List<String> bodies = drain(MentionTopology.QUEUE);
    assertThat(bodies).hasSize(8);
    assertThat(messageIdsIn(bodies)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
  }

  @Test
  void anEventNoQueueReceives_isReturnedByTheBroker_andStaysPending() {
    outbox.add("nadie-escucha", Map.of("x", 1));

    assertThat(publisher(realTransport).drainOnce()).isZero();

    // Sin «mandatory», RabbitMQ lo confirmaría y lo descartaría: el evento se perdería para
    // siempre.
    assertThat(pendingEvents()).isEqualTo(1);
  }

  @Test
  void twoPublishersAtTheSameTime_publishEachEventExactlyOnce() throws Exception {
    int total = 500;
    addMentions(total);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> drainAll =
          () -> {
            int sum = 0;
            int n;
            OutboxPublisher mine = publisher(realTransport);
            while ((n = mine.drainOnce()) > 0) {
              sum += n;
            }
            return sum;
          };
      Future<Integer> a = pool.submit(drainAll);
      Future<Integer> b = pool.submit(drainAll);

      int publishedByBoth = a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS);

      assertThat(publishedByBoth).isEqualTo(total);
    } finally {
      pool.shutdownNow();
    }
    assertThat(pendingEvents()).isZero();
    List<String> bodies = drain(MentionTopology.QUEUE);
    assertThat(bodies).as("ni uno más ni uno menos").hasSize(total);
    assertThat(messageIdsIn(bodies))
        .as("ninguno repetido")
        .containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList());
  }
}
