package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jsav.chat.presence.PresenceRefresher;
import com.jsav.chat.presence.PresenceStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * La presencia sigue a los WebSockets reales: abrir una pestaña enciende al usuario, cerrar la
 * última lo apaga, y funciona entre instancias (DESIGN.md §5, ADR 0008).
 */
class PresenceTrackingIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration SETTLES = Duration.ofSeconds(10);

  @Autowired PresenceStore store;
  @Autowired PresenceRefresher refresher;
  @Autowired StringRedisTemplate redis;

  private boolean online(Long userId) {
    return store.online(List.of(userId)).contains(userId);
  }

  @Test
  void connecting_turnsTheUserOnline_andClosingTheOnlyTabTurnsThemOff() {
    assertThat(online(member.getId())).isFalse();

    StompTestClient tab = connect(member);
    await().atMost(SETTLES).until(() -> online(member.getId()));

    tab.close();
    await().atMost(SETTLES).until(() -> !online(member.getId()));
  }

  @Test
  void withTwoTabs_closingOneKeepsTheUserOnline_andClosingBothTurnsThemOff() {
    StompTestClient first = connect(member);
    StompTestClient second = connect(member);
    await().atMost(SETTLES).until(() -> online(member.getId()));

    first.close();
    await().pollDelay(Duration.ofSeconds(1)).atMost(SETTLES).until(() -> online(member.getId()));
    assertThat(online(member.getId())).isTrue();

    second.close();
    await().atMost(SETTLES).until(() -> !online(member.getId()));
  }

  @Test
  void aTabOnTheOtherInstance_countsAsOnline_fromInstanceA() {
    startInstanceB();
    try (StompTestClient onB = connectToB(member)) {
      // Esta es la instancia A: la presencia está en Redis, no en memoria.
      await().atMost(SETTLES).until(() -> online(member.getId()));
      assertThat(onB.isConnected()).isTrue();
    }
    await().atMost(SETTLES).until(() -> !online(member.getId()));
  }

  @Test
  void aKickedUserGoesOffline() {
    StompTestClient tab = connect(member);
    await().atMost(SETTLES).until(() -> online(member.getId()));

    serverService.kick(owner.getId(), serverId, member.getId());

    await().atMost(SETTLES).until(() -> !online(member.getId()));
    tab.close();
  }

  @Test
  void theHeartbeatBringsBackAPresenceRedisLost() {
    try (StompTestClient tab = connect(member)) {
      await().atMost(SETTLES).until(() -> online(member.getId()));

      redis.delete("presence:" + member.getId());
      assertThat(online(member.getId())).isFalse();

      refresher.refresh();

      assertThat(online(member.getId())).isTrue();
    }
  }

  @Test
  void theHeartbeatDoesNotBringBackAClosedConnection() {
    StompTestClient tab = connect(member);
    await().atMost(SETTLES).until(() -> online(member.getId()));
    tab.close();
    await().atMost(SETTLES).until(() -> !online(member.getId()));

    refresher.refresh();

    assertThat(online(member.getId())).isFalse();
  }
}
