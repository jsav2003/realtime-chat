package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Ningún cliente puede publicar por STOMP: se escribe solo por HTTP (ADR 0006). */
class StompSendIntegrationTest extends RealtimeIntegrationTest {

  private static final String FORGED = "{\"id\":1,\"channelId\":1,\"seq\":999,\"body\":\"falso\"}";

  @Test
  void aSubscriberCannotPublishStraightToTheChannelTopic() {
    try (StompTestClient attacker = connect(member);
        StompTestClient victim = connect(admin)) {
      assertThat(attacker.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(victim.subscribe(ChannelTopic.of(general))).isNull();

      attacker.send(ChannelTopic.of(general), FORGED);

      assertThat(attacker.pollError(Duration.ofSeconds(5))).startsWith("403");
      assertThat(victim.nextMessage(Duration.ofSeconds(2))).isNull();
    }
  }

  @Test
  void anOutsiderCannotPublishToAChannelHeDoesNotSee() {
    try (StompTestClient listener = connect(member);
        StompTestClient outsiderClient = connect(outsider)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      outsiderClient.send(ChannelTopic.of(general), FORGED);

      assertThat(outsiderClient.pollError(Duration.ofSeconds(5))).startsWith("403");
      assertThat(listener.nextMessage(Duration.ofSeconds(2))).isNull();
    }
  }

  @Test
  void aSendToAnApplicationDestinationIsRejectedToo() {
    try (StompTestClient c = connect(member)) {
      c.send("/app/channels/" + general + "/messages", FORGED);

      assertThat(c.pollError(Duration.ofSeconds(5))).startsWith("403");
    }
  }
}
