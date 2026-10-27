package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** El aviso de expulsión viaja por RabbitMQ y la instancia que lo recibe corta los sockets. */
class ControlChannelIntegrationTest extends RealtimeIntegrationTest {

  @Autowired ControlChannel control;

  @Test
  void aKickAnnouncementClosesTheUsersSocket() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(c.isConnected()).isTrue();

      control.announceKicked(member.getId());

      assertThat(c.awaitClosed(Duration.ofSeconds(10))).isTrue();
    }
  }

  @Test
  void aKickAnnouncementLeavesOtherUsersConnected() {
    try (StompTestClient kicked = connect(member);
        StompTestClient bystander = connect(admin)) {
      assertThat(bystander.subscribe(ChannelTopic.of(general))).isNull();

      control.announceKicked(member.getId());

      assertThat(kicked.awaitClosed(Duration.ofSeconds(10))).isTrue();
      assertThat(bystander.isConnected()).isTrue();
    }
  }
}
