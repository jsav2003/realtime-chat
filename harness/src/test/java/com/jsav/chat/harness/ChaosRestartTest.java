package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** El reinicio se dispara por progreso, una sola vez y solo si la corrida llega a ese punto. */
class ChaosRestartTest {

  private static ScenarioPlan plan(int clients, int messages, double restartAt) {
    Scenario sc = Scenario.DEFAULTS;
    return ScenarioPlan.generate(
        new Scenario(
            sc.seed(),
            clients,
            messages,
            0,
            0, // sin desconexiones: aquí solo importa el reinicio
            restartAt,
            sc.lbUrl(),
            sc.instanceAUrl(),
            sc.instanceBUrl()));
  }

  @Test
  void restartsTheChosenInstanceOnceTheRunReachesThePoint() throws Exception {
    ScenarioPlan plan = plan(2, 10, 0.5); // 20 envíos, reinicio al llegar a 10
    Progress progress = new Progress();
    List<Instance> restarts = new CopyOnWriteArrayList<>();
    Chaos chaos = new Chaos(plan, new ArrayList<>(), progress, restarts::add);
    chaos.start();

    for (int i = 0; i < 9; i++) {
      progress.oneConfirmed();
    }
    Thread.sleep(700);
    assertThat(restarts).as("todavía no llegó al punto").isEmpty();

    progress.oneConfirmed();
    progress.finish();
    chaos.finish();

    assertThat(restarts).containsExactly(plan.restart().instance());
    assertThat(chaos.restartedInstance()).isEqualTo(plan.restart().instance());
    assertThat(chaos.restartMillis()).isGreaterThanOrEqualTo(0);
    assertThat(chaos.restartError()).isNull();
  }

  @Test
  void doesNotRestartIfTheRunEndsBeforeThePoint() throws Exception {
    ScenarioPlan plan = plan(2, 10, 1.0); // reinicio recién al confirmar los 20
    Progress progress = new Progress();
    List<Instance> restarts = new CopyOnWriteArrayList<>();
    Chaos chaos = new Chaos(plan, new ArrayList<>(), progress, restarts::add);
    chaos.start();

    progress.oneConfirmed();
    progress.finish();
    chaos.finish();

    assertThat(restarts).isEmpty();
    assertThat(chaos.restartedInstance()).isNull();
  }

  @Test
  void aFailedRestartIsReported_notSwallowed() throws Exception {
    ScenarioPlan plan = plan(2, 10, 0.1);
    Progress progress = new Progress();
    Chaos chaos =
        new Chaos(
            plan,
            new ArrayList<>(),
            progress,
            i -> {
              throw new IllegalStateException("docker no responde");
            });
    chaos.start();

    progress.oneConfirmed();
    progress.oneConfirmed();
    progress.finish();
    chaos.finish();

    assertThat(chaos.restartError()).contains("docker no responde");
    assertThat(chaos.restartedInstance()).isNull();
  }
}
