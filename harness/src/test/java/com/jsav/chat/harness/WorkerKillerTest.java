package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.WorkerKiller.Kill;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** El worker se mata por progreso, en orden, y siempre se deja levantado al final. */
class WorkerKillerTest {

  /** Un contenedor de mentira que anota lo que le hacen. */
  private static final class FakeWorker implements WorkerKiller.Container {
    final List<String> events = new CopyOnWriteArrayList<>();
    volatile boolean failOnKill;

    @Override
    public void kill() {
      if (failOnKill) {
        throw new IllegalStateException("docker no responde");
      }
      events.add("kill");
    }

    @Override
    public void start() {
      events.add("start");
    }
  }

  @Test
  void thePlanIsTheSameForTheSameSeed_sortedAndInsideTheRun() {
    List<Kill> a = WorkerKiller.plan(42, 6, 320);
    List<Kill> b = WorkerKiller.plan(42, 6, 320);

    assertThat(a).isEqualTo(b).hasSize(6);
    assertThat(a).extracting(Kill::atConfirmed).isSorted().allMatch(p -> p >= 0 && p < 320);
    assertThat(a).extracting(Kill::downMillis).allMatch(ms -> ms >= 300 && ms < 3000);
    assertThat(WorkerKiller.plan(43, 6, 320)).isNotEqualTo(a);
  }

  @Test
  void noKillsMeansAnEmptyPlan() {
    assertThat(WorkerKiller.plan(42, 0, 320)).isEmpty();
  }

  @Test
  void killsTheWorkerOnceTheRunReachesEachPoint_thenBringsItBack() throws Exception {
    FakeWorker worker = new FakeWorker();
    Progress progress = new Progress();
    WorkerKiller killer =
        new WorkerKiller(List.of(new Kill(3, 10), new Kill(6, 10)), progress, worker);
    killer.start();

    for (int i = 0; i < 2; i++) {
      progress.oneConfirmed();
    }
    Thread.sleep(300);
    assertThat(worker.events).as("todavía no llegó al primer punto").isEmpty();

    for (int i = 0; i < 4; i++) {
      progress.oneConfirmed();
    }
    progress.finish();
    killer.finish();

    // Una muerte y su regreso, dos veces, y al final se asegura que quede levantado.
    assertThat(worker.events).containsExactly("kill", "start", "kill", "start", "start");
    assertThat(killer.kills()).isEqualTo(2);
    assertThat(killer.error()).isNull();
  }

  @Test
  void doesNotKillIfTheRunEndsBeforeThePoint() throws Exception {
    FakeWorker worker = new FakeWorker();
    Progress progress = new Progress();
    WorkerKiller killer = new WorkerKiller(List.of(new Kill(50, 10)), progress, worker);
    killer.start();

    progress.oneConfirmed();
    progress.finish();
    killer.finish();

    assertThat(killer.kills()).isZero();
    assertThat(worker.events).containsExactly("start"); // solo el «asegurar que quede levantado»
  }

  @Test
  void aFailedKillIsReported_andTheWorkerIsStillLeftRunning() throws Exception {
    FakeWorker worker = new FakeWorker();
    worker.failOnKill = true;
    Progress progress = new Progress();
    WorkerKiller killer = new WorkerKiller(List.of(new Kill(1, 10)), progress, worker);
    killer.start();

    progress.oneConfirmed();
    progress.finish();
    killer.finish();

    assertThat(killer.kills()).isZero();
    assertThat(killer.error()).contains("docker no responde");
    assertThat(worker.events).containsExactly("start");
  }
}
