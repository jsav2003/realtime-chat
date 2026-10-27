package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** El aviso de expulsión viaja por RabbitMQ: las demás instancias cortan, la que avisa no. */
class ControlChannelIntegrationTest extends RealtimeIntegrationTest {

  @Autowired ControlChannel control;

  @Test
  void anAnnouncementFromAnotherInstanceClosesTheUsersSocket() {
    startInstanceB();
    ControlChannel fromB = instanceBBean(ControlChannel.class);
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();

      fromB.announceKicked(member.getId());

      assertThat(c.awaitClosed(Duration.ofSeconds(10))).isTrue();
    }
  }

  @Test
  void anAnnouncementLeavesOtherUsersConnected() {
    startInstanceB();
    ControlChannel fromB = instanceBBean(ControlChannel.class);
    try (StompTestClient kicked = connect(member);
        StompTestClient bystander = connect(admin)) {
      assertThat(bystander.subscribe(ChannelTopic.of(general))).isNull();

      fromB.announceKicked(member.getId());

      assertThat(kicked.awaitClosed(Duration.ofSeconds(10))).isTrue();
      assertThat(bystander.isConnected()).isTrue();
    }
  }

  @Test
  void anInstanceIgnoresItsOwnAnnouncement() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();

      control.announceKicked(member.getId());

      // Cerrar los sockets propios es trabajo de KickBroadcaster; el eco no debe tocarlos.
      assertThat(c.awaitClosed(Duration.ofSeconds(3))).isFalse();
      assertThat(c.isConnected()).isTrue();
    }
  }
}
