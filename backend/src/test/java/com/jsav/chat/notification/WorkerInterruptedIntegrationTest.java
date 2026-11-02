package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jsav.chat.PipelineIntegrationTest;
import com.jsav.chat.SimulatedCrash;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.outbox.OutboxPublisher;
import com.jsav.chat.outbox.OutboxTransport;
import com.jsav.chat.user.User;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DESIGN.md §8.3: se mata al worker en momentos aleatorios y toda mención termina notificada
 * exactamente una vez. Lo que se mata, mientras los mensajes siguen entrando:
 *
 * <ul>
 *   <li><b>El worker</b>, antes de procesar un evento y después de guardar su notificación pero
 *       antes de confirmarlo (el caso que obliga a deduplicar), al azar con semilla fija.
 *   <li><b>Su conexión</b> a RabbitMQ, cortada a la fuerza varias veces: lo que estaba en vuelo
 *       queda sin confirmar y se entrega otra vez. Y el worker entero, parado y vuelto a arrancar.
 *   <li><b>El publicador</b>, justo después de publicar un lote y antes de marcarlo: ese lote sale
 *       dos veces.
 * </ul>
 *
 * <p>Al terminar, contra lo que se envió: (1) cada (mensaje, persona) mencionada tiene exactamente
 * una notificación, (2) no hay ninguna de más, (3) la outbox no tiene pendientes y (4) la cola de
 * mensajes muertos está vacía. Y se comprueba que la corrida <b>sí</b> tocó las ventanas
 * peligrosas: sin caídas ni duplicados descartados, un pase no probaría nada.
 */
class WorkerInterruptedIntegrationTest extends PipelineIntegrationTest {

  private static final int MEMBERS = 6;
  private static final int SENDERS = 4;
  private static final int MESSAGES_PER_SENDER = 50;

  @Autowired CachingConnectionFactory connectionFactory;

  /** Un mensaje que se va a enviar y a quién menciona. */
  private record Planned(User author, List<User> mentioned) {}

  /** Lo que el servidor confirmó haber guardado: (mensaje, persona mencionada). */
  private record Pair(long messageId, long userId) {}

  private static List<Planned> plan(Random rnd, List<User> everyone, User author, int count) {
    List<Planned> plans = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      List<User> others = new ArrayList<>(everyone);
      others.remove(author);
      Collections.shuffle(others, rnd);
      int howMany = 1 + rnd.nextInt(3); // de 1 a 3 personas
      plans.add(new Planned(author, List.copyOf(others.subList(0, howMany))));
    }
    return plans;
  }

  @ParameterizedTest(name = "semilla {0}")
  @ValueSource(longs = {42, 7, 2026})
  void everyMentionIsNotifiedExactlyOnce_whileTheWorkerAndThePublisherKeepDying(long seed)
      throws Exception {
    World w = newWorld(MEMBERS);
    List<User> everyone = new ArrayList<>(w.members());
    everyone.add(w.owner());
    Random rnd = new Random(seed);
    List<List<Planned>> plans = new ArrayList<>();
    for (int s = 0; s < SENDERS; s++) {
      plans.add(plan(rnd, everyone, everyone.get(s), MESSAGES_PER_SENDER));
    }

    double duplicatesBefore = counter(MentionWorker.DUPLICATES);
    crashes.arm(seed, 0.20, 0.20);
    AtomicBoolean publisherMayCrash = new AtomicBoolean(true);
    AtomicInteger publisherCrashes = new AtomicInteger();
    Random publisherRandom = new Random(seed + 1);
    OutboxTransport dyingTransport =
        events -> {
          Set<Long> confirmed = realTransport.publish(events);
          boolean die;
          synchronized (publisherRandom) {
            die = publisherMayCrash.get() && publisherRandom.nextDouble() < 0.30;
          }
          if (die) {
            throw new SimulatedCrash("publicador: después de publicar, antes de marcar");
          }
          return confirmed;
        };

    startWorker();
    AtomicBoolean running = new AtomicBoolean(true);
    ExecutorService background = Executors.newCachedThreadPool();
    // Dos publicadores, como dos instancias del backend. Si uno «muere», arranca de nuevo.
    for (int i = 0; i < 2; i++) {
      background.submit(
          () -> {
            OutboxPublisher publisher =
                new OutboxPublisher(outbox, dyingTransport, tx, outboxProps);
            while (running.get()) {
              try {
                publisher.drainOnce();
              } catch (SimulatedCrash e) {
                publisherCrashes.incrementAndGet();
              } catch (RuntimeException e) {
                // Un corte de conexión a media publicación: la vuelta siguiente lo reintenta.
              }
              pause(20);
            }
          });
    }
    // El caos sobre el worker: conexión cortada a la fuerza y worker parado y arrancado.
    Random chaosRandom = new Random(seed + 2);
    Future<?> chaos =
        background.submit(
            () -> {
              for (int i = 0; i < 6 && running.get(); i++) {
                pause(200 + chaosRandom.nextInt(400));
                connectionFactory.resetConnection();
                if (i % 2 == 1) {
                  stopWorkerContainer();
                  pause(100);
                  startWorker();
                }
              }
            });

    // Los mensajes siguen entrando mientras todo eso pasa.
    List<Pair> expected = Collections.synchronizedList(new ArrayList<>());
    ExecutorService senders = Executors.newFixedThreadPool(SENDERS);
    List<Future<?>> sending = new ArrayList<>();
    for (List<Planned> mine : plans) {
      sending.add(
          senders.submit(
              () -> {
                for (Planned p : mine) {
                  String body = "ey " + w.mentioning(p.mentioned().toArray(User[]::new));
                  long messageId =
                      messages
                          .send(
                              p.author().getId(),
                              w.channelId(),
                              new SendMessageRequest(UUID.randomUUID(), body))
                          .message()
                          .id();
                  p.mentioned().forEach(u -> expected.add(new Pair(messageId, u.getId())));
                  pause(10);
                }
              }));
    }
    for (Future<?> f : sending) {
      f.get(60, TimeUnit.SECONDS);
    }
    senders.shutdown();
    chaos.get(60, TimeUnit.SECONDS);

    // Se acaba el caos: lo que queda es que el sistema termine por sí solo lo que dejó a medias.
    int workerCrashes = crashes.crashedBefore() + crashes.crashedAfter();
    int crashedAfterSaving = crashes.crashedAfter();
    crashes.disarm();
    publisherMayCrash.set(false);
    startWorker();

    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .until(() -> pendingEvents() == 0 && notificationCount() >= expected.size());
    pause(1500); // que se note si algo todavía iba a aparecer
    running.set(false);
    background.shutdown();
    assertThat(background.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    Set<Pair> actual = new HashSet<>();
    jdbc.sql("select message_id, user_id from notifications")
        .query((rs, i) -> new Pair(rs.getLong("message_id"), rs.getLong("user_id")))
        .list()
        .forEach(actual::add);
    Set<Pair> wanted = new HashSet<>(expected);

    assertThat(wanted).as("cada mención del plan es distinta").hasSize(expected.size());
    assertThat(actual).as("(1) y (2): exactamente lo que se mencionó").isEqualTo(wanted);
    assertThat(notificationCount()).as("sin filas repetidas").isEqualTo(wanted.size());
    assertThat(pendingEvents()).as("(3) la outbox quedó vacía").isZero();
    assertThat(admin.getQueueInfo(MentionTopology.DEAD_LETTERS).getMessageCount())
        .as("(4) nada terminó en la cola de mensajes muertos")
        .isZero();
    assertThat(admin.getQueueInfo(MentionTopology.QUEUE).getMessageCount())
        .as("la cola principal quedó vacía")
        .isZero();

    // La corrida tocó lo que tenía que tocar.
    assertThat(workerCrashes).as("caídas del worker inyectadas").isPositive();
    assertThat(crashedAfterSaving)
        .as("caídas después de guardar y antes de confirmar")
        .isPositive();
    assertThat(publisherCrashes.get()).as("caídas del publicador inyectadas").isPositive();
    double duplicates = counter(MentionWorker.DUPLICATES) - duplicatesBefore;
    assertThat(duplicates).as("eventos repetidos que el worker descartó").isPositive();
    System.out.printf(
        "[semilla %d] menciones=%d caidas_worker=%d (despues_de_guardar=%d) caidas_publicador=%d"
            + " duplicados_descartados=%.0f%n",
        seed, wanted.size(), workerCrashes, crashedAfterSaving, publisherCrashes.get(), duplicates);
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
