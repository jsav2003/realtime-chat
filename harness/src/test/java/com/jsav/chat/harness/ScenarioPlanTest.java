package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScenarioPlanTest {

  private static final Scenario BASE = Scenario.DEFAULTS;

  private static Scenario withSeed(long seed) {
    return new Scenario(
        seed,
        BASE.clients(),
        BASE.messagesPerClient(),
        BASE.duplicateRate(),
        BASE.disconnects(),
        BASE.restartAt(),
        BASE.lbUrl(),
        BASE.instanceAUrl(),
        BASE.instanceBUrl());
  }

  @Test
  void theSameSeedGivesTheSamePlan() {
    ScenarioPlan a = ScenarioPlan.generate(withSeed(42));
    ScenarioPlan b = ScenarioPlan.generate(withSeed(42));

    for (int c = 0; c < BASE.clients(); c++) {
      assertThat(a.sendsOf(c)).isEqualTo(b.sendsOf(c));
      assertThat(a.startInstanceOf(c)).isEqualTo(b.startInstanceOf(c));
    }
    assertThat(a.disconnects()).isEqualTo(b.disconnects());
    assertThat(a.restart()).isEqualTo(b.restart());
  }

  @Test
  void anotherSeedGivesAnotherPlan() {
    ScenarioPlan a = ScenarioPlan.generate(withSeed(42));
    ScenarioPlan b = ScenarioPlan.generate(withSeed(43));

    assertThat(a.disconnects()).isNotEqualTo(b.disconnects());
  }

  @Test
  void everyClientHasItsMessages_withUniqueClientMsgIds() {
    ScenarioPlan plan = ScenarioPlan.generate(withSeed(7));
    Set<UUID> ids = new HashSet<>();

    for (int c = 0; c < BASE.clients(); c++) {
      List<ScenarioPlan.Send> sends = plan.sendsOf(c);
      assertThat(sends).hasSize(BASE.messagesPerClient());
      sends.forEach(s -> assertThat(ids.add(s.clientMsgId())).isTrue());
    }
    assertThat(ids).hasSize(BASE.totalMessages());
  }

  @Test
  void chaosIsOrderedByProgress_andInsideTheRun() {
    ScenarioPlan plan = ScenarioPlan.generate(withSeed(7));

    assertThat(plan.disconnects()).hasSize(BASE.disconnects());
    assertThat(plan.disconnects())
        .isSortedAccordingTo((x, y) -> Integer.compare(x.atConfirmed(), y.atConfirmed()));
    plan.disconnects()
        .forEach(
            d -> {
              assertThat(d.atConfirmed()).isBetween(0, BASE.totalMessages() - 1);
              assertThat(d.client()).isBetween(0, BASE.clients() - 1);
            });
    assertThat(plan.restart().atConfirmed())
        .isEqualTo((int) Math.round(BASE.totalMessages() * 0.4));
  }

  @Test
  void aboutTheRequestedShareOfSendsIsResent() {
    ScenarioPlan plan = ScenarioPlan.generate(withSeed(7));

    assertThat(plan.plannedResends()).isBetween(30L, 120L); // 15 % de 480 es 72
  }

  @Test
  void argumentsOverrideTheDefaults() {
    Scenario s = Scenario.fromArgs(new String[] {"--seed", "9", "--clients", "3"});

    assertThat(s.seed()).isEqualTo(9);
    assertThat(s.clients()).isEqualTo(3);
    assertThat(s.messagesPerClient()).isEqualTo(BASE.messagesPerClient());
  }

  @Test
  void unknownArgumentsAreRejected() {
    assertThatThrownBy(() -> Scenario.fromArgs(new String[] {"--seeed", "9"}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
