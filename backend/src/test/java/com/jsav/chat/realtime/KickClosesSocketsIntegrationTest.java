package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Expulsar corta los WebSockets del expulsado, en cualquier instancia (DESIGN.md §4, ADR 0007). */
class KickClosesSocketsIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration CLOSES_WITHIN = Duration.ofSeconds(10);

  @Autowired TransactionTemplate tx;

  @Test
  void kickingAMember_closesTheirOpenSocket_andOnlyTheirs() {
    try (StompTestClient kicked = connect(member);
        StompTestClient bystander = connect(admin)) {
      assertThat(kicked.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(bystander.subscribe(ChannelTopic.of(general))).isNull();

      serverService.kick(owner.getId(), serverId, member.getId());

      assertThat(kicked.awaitClosed(CLOSES_WITHIN)).isTrue();
      assertThat(bystander.isConnected()).isTrue();
    }
  }

  @Test
  void kickingAMember_closesTheirSocketOnTheOtherInstance() {
    startInstanceB();
    try (StompTestClient kickedOnB = connectToB(member)) {
      assertThat(kickedOnB.subscribe(ChannelTopic.of(general))).isNull();

      // La expulsión se atiende en la instancia A; el socket del expulsado vive en la B.
      serverService.kick(owner.getId(), serverId, member.getId());

      assertThat(kickedOnB.awaitClosed(CLOSES_WITHIN)).isTrue();
    }
  }

  @Test
  void aKickThatRollsBack_closesNothing() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();

      tx.executeWithoutResult(
          status -> {
            serverService.kick(owner.getId(), serverId, member.getId());
            status.setRollbackOnly();
          });

      assertThat(c.awaitClosed(Duration.ofSeconds(3))).isFalse();
      assertThat(c.isConnected()).isTrue();
      // Y sigue siendo miembro: puede leer el canal.
      assertThat(
              serverMembers.findById(
                  new com.jsav.chat.server.ServerMemberId(serverId, member.getId())))
          .isPresent();
    }
  }
}
