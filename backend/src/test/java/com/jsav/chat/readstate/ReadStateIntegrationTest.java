package com.jsav.chat.readstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.readstate.ReadStateDtos.ChannelUnread;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El cursor de lectura (ADR 0015): sube, nunca baja, nunca pasa del último {@code seq} y solo lo
 * mueve quien puede leer el canal. Postgres real.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReadStateIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired ReadStateService service;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  User owner;
  User ana;
  User beto;
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("rs" + run + "-o@x.test", "x", "Owner", "rs" + run + "_o"));
    ana = users.save(new User("rs" + run + "-a@x.test", "x", "Ana", "rs" + run + "_a"));
    beto = users.save(new User("rs" + run + "-b@x.test", "x", "Beto", "rs" + run + "_b"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Rs" + run)).id();
    serverMembers.save(new ServerMember(serverId, ana.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, beto.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    channelService.addMember(owner.getId(), secret, ana.getId());
  }

  private void send(Long channelId, int count) {
    for (int i = 0; i < count; i++) {
      messages.send(
          owner.getId(), channelId, new SendMessageRequest(UUID.randomUUID(), "mensaje " + i));
    }
  }

  private Optional<Long> lastRead(User who, Long channelId) {
    return jdbc.sql("select last_read_seq from read_state where user_id = :u and channel_id = :c")
        .param("u", who.getId())
        .param("c", channelId)
        .query(Long.class)
        .optional();
  }

  @Test
  void withoutMarkingAnything_thereIsNoRow() {
    send(general, 3);

    assertThat(lastRead(ana, general)).isEmpty();
  }

  @Test
  void markingMovesTheCursorUp() {
    send(general, 5);

    service.markRead(ana.getId(), general, 3);

    assertThat(lastRead(ana, general)).contains(3L);
  }

  @Test
  void theCursorNeverGoesBack() {
    send(general, 20);
    service.markRead(ana.getId(), general, 20);

    service.markRead(ana.getId(), general, 10);

    assertThat(lastRead(ana, general)).contains(20L);
  }

  @Test
  void theCursorIsCappedAtTheChannelsLastSeq_soLaterMessagesStayUnread() {
    send(general, 2);

    service.markRead(ana.getId(), general, 999);
    assertThat(lastRead(ana, general)).contains(2L);

    send(general, 1);
    assertThat(lastRead(ana, general)).contains(2L); // el 3 sigue sin leer
  }

  @Test
  void eachPersonHasTheirOwnCursor() {
    send(general, 4);

    service.markRead(ana.getId(), general, 4);

    assertThat(lastRead(ana, general)).contains(4L);
    assertThat(lastRead(beto, general)).isEmpty();
  }

  @Test
  void aStranger_getsNotFound_andNoRowIsCreated() {
    User outsider =
        users.save(
            new User("rs-out" + owner.getId() + "@x.test", "x", "Out", "rsout" + owner.getId()));
    send(general, 1);

    assertThatThrownBy(() -> service.markRead(outsider.getId(), general, 1))
        .isInstanceOf(NotFoundException.class);

    assertThat(lastRead(outsider, general)).isEmpty();
  }

  @Test
  void aPrivateChannelYouAreNotIn_getsNotFound_andNoRowIsCreated() {
    send(secret, 2);

    assertThatThrownBy(() -> service.markRead(beto.getId(), secret, 2))
        .isInstanceOf(NotFoundException.class);

    assertThat(lastRead(beto, secret)).isEmpty();
  }

  @Test
  void aKickedMember_cannotMarkAnymore() {
    send(general, 1);
    serverService.kick(owner.getId(), serverId, ana.getId());

    assertThatThrownBy(() -> service.markRead(ana.getId(), general, 1))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- la resta de no leídos (DESIGN.md §3.3) ----

  private ChannelUnread unreadOf(User who, Long channelId) {
    return service.unread(who.getId(), serverId).channels().stream()
        .filter(c -> c.channelId() == channelId.longValue())
        .findFirst()
        .orElseThrow();
  }

  private boolean sees(User who, Long channelId) {
    return service.unread(who.getId(), serverId).channels().stream()
        .anyMatch(c -> c.channelId() == channelId.longValue());
  }

  @Test
  void withoutAnyMark_everythingIsUnread() {
    send(general, 5);

    ChannelUnread u = unreadOf(beto, general);

    assertThat(u.lastSeq()).isEqualTo(5);
    assertThat(u.lastReadSeq()).isZero();
    assertThat(u.unread()).isEqualTo(5);
  }

  @Test
  void unread_isLastSeqMinusLastReadSeq() {
    send(general, 5);
    service.markRead(ana.getId(), general, 2);

    ChannelUnread u = unreadOf(ana, general);

    assertThat(u.lastSeq()).isEqualTo(5);
    assertThat(u.lastReadSeq()).isEqualTo(2);
    assertThat(u.unread()).isEqualTo(3);
  }

  @Test
  void markingEverything_leavesZero_andANewMessageMakesItOne() {
    send(general, 2);
    service.markRead(ana.getId(), general, 999);
    assertThat(unreadOf(ana, general).unread()).isZero();

    send(general, 1);

    assertThat(unreadOf(ana, general).unread()).isEqualTo(1);
  }

  @Test
  void theSubtractionEqualsTheRealCount_evenWithRetriedSends() {
    // Tres mensajes distintos y uno reenviado con la misma clave: el reintento no gasta seq.
    UUID retried = UUID.randomUUID();
    messages.send(owner.getId(), general, new SendMessageRequest(retried, "uno"));
    messages.send(owner.getId(), general, new SendMessageRequest(retried, "uno"));
    send(general, 2);

    Long counted =
        jdbc.sql("select count(*) from messages where channel_id = :c")
            .param("c", general)
            .query(Long.class)
            .single();

    assertThat(unreadOf(beto, general).unread()).isEqualTo(counted).isEqualTo(3);
  }

  @Test
  void aPrivateChannelYouAreNotIn_isNotInYourList() {
    send(secret, 2);

    assertThat(sees(ana, secret)).isTrue();
    assertThat(sees(beto, secret)).isFalse();
    assertThat(sees(beto, general)).isTrue();
    assertThat(sees(owner, secret)).isTrue(); // el owner ve todos los privados
  }

  @Test
  void takenOutOfAPrivateChannel_itLeavesTheList() {
    send(secret, 2);
    service.markRead(ana.getId(), secret, 1);
    channelService.removeMember(owner.getId(), secret, ana.getId());

    assertThat(sees(ana, secret)).isFalse();
    assertThat(sees(ana, general)).isTrue();
  }

  @Test
  void aStrangerAndAKickedMember_getNotFoundForTheServer() {
    User outsider =
        users.save(
            new User("rs-out2" + owner.getId() + "@x.test", "x", "Out", "rso2" + owner.getId()));
    serverService.kick(owner.getId(), serverId, ana.getId());

    assertThatThrownBy(() -> service.unread(outsider.getId(), serverId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.unread(ana.getId(), serverId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aChannelThatDoesNotExist_getsNotFound() {
    assertThatThrownBy(() -> service.markRead(ana.getId(), 987_654_321L, 1))
        .isInstanceOf(NotFoundException.class);
  }
}
