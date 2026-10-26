package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.message.MessageService.Sent;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Envío de mensajes: reintentos, clave repetida en otro canal y acceso (DESIGN.md §3.2, §4). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessageServiceIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired MessageService service;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  User owner;
  User member;
  User outsider;
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("svc" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(new User("svc" + run + "-member@x.test", "x", "Member"));
    outsider = users.save(new User("svc" + run + "-out@x.test", "x", "Outsider"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Svc" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
  }

  private static SendMessageRequest req(UUID key, String body) {
    return new SendMessageRequest(key, body);
  }

  private long lastSeq(Long channelId) {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  @Test
  void send_assignsConsecutiveSeqs() {
    Sent first = service.send(member.getId(), general, req(UUID.randomUUID(), "uno"));
    Sent second = service.send(owner.getId(), general, req(UUID.randomUUID(), "dos"));

    assertThat(first.replayed()).isFalse();
    assertThat(first.message().seq()).isEqualTo(1);
    assertThat(second.message().seq()).isEqualTo(2);
    assertThat(second.message().authorName()).isEqualTo("Owner");
  }

  @Test
  void send_stripsSurroundingWhitespace() {
    Sent sent = service.send(member.getId(), general, req(UUID.randomUUID(), "  hola \n"));

    assertThat(sent.message().body()).isEqualTo("hola");
  }

  @Test
  void retry_withSameClientMsgId_returnsTheSameMessage_andSpendsNoSeq() {
    UUID key = UUID.randomUUID();
    Sent original = service.send(member.getId(), general, req(key, "hola"));

    Sent retry = service.send(member.getId(), general, req(key, "hola"));

    assertThat(retry.replayed()).isTrue();
    assertThat(retry.message()).isEqualTo(original.message());
    assertThat(lastSeq(general)).isEqualTo(1);
  }

  @Test
  void retry_keepsTheOriginalBody_evenIfTheRetrySendsAnother() {
    UUID key = UUID.randomUUID();
    service.send(member.getId(), general, req(key, "original"));

    Sent retry = service.send(member.getId(), general, req(key, "cambiado"));

    assertThat(retry.message().body()).isEqualTo("original");
  }

  @Test
  void sameClientMsgId_fromAnotherAuthor_isAnotherMessage() {
    UUID key = UUID.randomUUID();
    service.send(member.getId(), general, req(key, "de member"));

    Sent fromOwner = service.send(owner.getId(), general, req(key, "de owner"));

    assertThat(fromOwner.replayed()).isFalse();
    assertThat(fromOwner.message().seq()).isEqualTo(2);
  }

  @Test
  void sameClientMsgId_inAnotherChannel_is409_andSpendsNoSeq() {
    UUID key = UUID.randomUUID();
    service.send(owner.getId(), general, req(key, "hola"));

    assertThatThrownBy(() -> service.send(owner.getId(), secret, req(key, "hola")))
        .isInstanceOf(ConflictException.class);
    assertThat(lastSeq(secret)).isZero();
  }

  @Test
  void outsider_gets404() {
    assertThatThrownBy(() -> service.send(outsider.getId(), general, req(UUID.randomUUID(), "x")))
        .isInstanceOf(NotFoundException.class);
    assertThat(lastSeq(general)).isZero();
  }

  @Test
  void memberNotInPrivateChannel_gets404() {
    assertThatThrownBy(() -> service.send(member.getId(), secret, req(UUID.randomUUID(), "x")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void kickedMember_cannotRetryEvenAMessageAlreadySaved() {
    UUID key = UUID.randomUUID();
    service.send(member.getId(), general, req(key, "antes de la expulsión"));
    serverService.kick(owner.getId(), serverId, member.getId());

    assertThatThrownBy(() -> service.send(member.getId(), general, req(key, "otra vez")))
        .isInstanceOf(NotFoundException.class);
  }
}
