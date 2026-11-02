package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.PipelineIntegrationTest;
import com.jsav.chat.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * La deduplicación del worker (ADR 0013) bajo concurrencia: varios workers que procesan <b>el mismo
 * evento a la vez</b> crean una sola notificación.
 *
 * <p>Es el equivalente, para las notificaciones, de «50 envíos simultáneos» (DESIGN.md §8.3), y
 * hace falta porque {@code WorkerInterruptedIntegrationTest} no lo cubre: en una caída, el evento
 * se entrega de nuevo <i>después</i> de que el primer intento terminó, y hasta una deduplicación
 * ingenua («¿ya existe?» y luego «créala») lo resuelve. Lo que rompe a la ingenua es que dos
 * workers pregunten a la vez, los dos reciban «no existe» y los dos creen la suya.
 */
class NotificationDedupeConcurrencyIntegrationTest extends PipelineIntegrationTest {

  private static final int PAIRS = 20;
  private static final int WORKERS_PER_PAIR = 8;

  @Autowired NotificationRepository notifications;

  @Test
  void manyWorkersProcessingTheSameEventAtOnce_createExactlyOneNotificationEach() throws Exception {
    World w = newWorld(PAIRS);
    long messageId = send(w.owner(), w.channelId(), "hola a todos");
    ExecutorService pool = Executors.newFixedThreadPool(PAIRS * WORKERS_PER_PAIR);
    try {
      CountDownLatch ready = new CountDownLatch(PAIRS * WORKERS_PER_PAIR);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Boolean>> created = new ArrayList<>();
      for (User member : w.members()) {
        for (int worker = 0; worker < WORKERS_PER_PAIR; worker++) {
          Callable<Boolean> process =
              () -> {
                ready.countDown();
                go.await();
                return notifications.insertIfAbsent(messageId, member.getId(), w.channelId());
              };
          created.add(pool.submit(process));
        }
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      go.countDown(); // todos a la vez

      long createdByAWorker = 0;
      for (Future<Boolean> f : created) {
        if (f.get(60, TimeUnit.SECONDS)) {
          createdByAWorker++;
        }
      }

      // De cada 8 workers que recibieron el mismo evento, uno solo lo creó y los otros 7 lo
      // descartaron como repetido.
      assertThat(createdByAWorker).as("notificaciones creadas").isEqualTo(PAIRS);
      assertThat(notificationCount()).as("filas en la tabla").isEqualTo(PAIRS);
      assertThat(notifiedFor(messageId)).hasSize(PAIRS).doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
  }
}
