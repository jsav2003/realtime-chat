package com.jsav.chat.readstate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
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

  @Test
  void aChannelThatDoesNotExist_getsNotFound() {
    assertThatThrownBy(() -> service.markRead(ana.getId(), 987_654_321L, 1))
        .isInstanceOf(NotFoundException.class);
  }
}
