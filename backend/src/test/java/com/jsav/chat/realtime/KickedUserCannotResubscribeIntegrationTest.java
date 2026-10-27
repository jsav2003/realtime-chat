package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * "Expulsado hace un segundo" (DESIGN.md §8.2): el JWT sigue vigente, así que puede volver a abrir
 * un WebSocket, pero {@code AccessPolicy} lee la base y le niega cada suscripción.
 */
class KickedUserCannotResubscribeIntegrationTest extends RealtimeIntegrationTest {

  @Test
  void afterTheKick_theSameTokenConnectsButCannotSubscribe() {
    String token = token(member);
    try (StompTestClient before = StompTestClient.connect(port, token)) {
      assertThat(before.subscribe(ChannelTopic.of(general))).isNull();

      serverService.kick(owner.getId(), serverId, member.getId());
      assertThat(before.awaitClosed(Duration.ofSeconds(10))).isTrue();
    }

    try (StompTestClient after = StompTestClient.connect(port, token)) {
      assertThat(after.subscribe(ChannelTopic.of(general))).startsWith("404");
    }
  }

  @Test
  void aKickedMemberOfAPrivateChannelLosesIt_too() {
    channelService.addMember(owner.getId(), secret, member.getId());
    try (StompTestClient before = connect(member)) {
      assertThat(before.subscribe(ChannelTopic.of(secret))).isNull();
    }

    serverService.kick(owner.getId(), serverId, member.getId());

    try (StompTestClient after = connect(member)) {
      assertThat(after.subscribe(ChannelTopic.of(secret))).startsWith("404");
    }
  }

  @Test
  void whoWasNotKickedKeepsTheirSubscriptions() {
    serverService.kick(owner.getId(), serverId, member.getId());

    try (StompTestClient c = connect(admin)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();
    }
  }
}
