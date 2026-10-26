package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.MessagePage;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Paginación por cursor sobre {@code seq}: últimos, hacia atrás y resincronización. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MessagePaginationIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired MessageService service;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  User owner;
  User member;
  Long general;
  Long secret;

  /** Siete mensajes en #general: seq 1 a 7. */
  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("page" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(new User("page" + run + "-member@x.test", "x", "Member"));
    Long serverId = serverService.create(owner.getId(), new CreateServerRequest("Pag" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    for (int i = 1; i <= 7; i++) {
      service.send(owner.getId(), general, new SendMessageRequest(UUID.randomUUID(), "m" + i));
    }
  }

  private static List<Long> seqs(MessagePage page) {
    return page.messages().stream().map(MessageResponse::seq).toList();
  }

  @Test
  void noCursor_returnsTheLatest_inAscendingOrder() {
    MessagePage page = service.list(member.getId(), general, null, null, 3);

    assertThat(seqs(page)).containsExactly(5L, 6L, 7L);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.messages().get(0).body()).isEqualTo("m5");
  }

  @Test
  void noCursor_withRoomForAll_hasNoMore() {
    MessagePage page = service.list(member.getId(), general, null, null, 50);

    assertThat(seqs(page)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void beforeSeq_walksBackwards_untilTheFirstMessage() {
    MessagePage older = service.list(member.getId(), general, null, 5L, 3);
    assertThat(seqs(older)).containsExactly(2L, 3L, 4L);
    assertThat(older.hasMore()).isTrue();

    MessagePage oldest = service.list(member.getId(), general, null, 2L, 3);
    assertThat(seqs(oldest)).containsExactly(1L);
    assertThat(oldest.hasMore()).isFalse();
  }

  @Test
  void afterSeq_returnsWhatCameNext_forResync() {
    MessagePage next = service.list(member.getId(), general, 4L, null, 2);
    assertThat(seqs(next)).containsExactly(5L, 6L);
    assertThat(next.hasMore()).isTrue();

    MessagePage rest = service.list(member.getId(), general, 6L, null, 2);
    assertThat(seqs(rest)).containsExactly(7L);
    assertThat(rest.hasMore()).isFalse();
  }

  @Test
  void afterSeqZero_startsFromTheFirstMessage() {
    assertThat(seqs(service.list(member.getId(), general, 0L, null, 2))).containsExactly(1L, 2L);
  }

  @Test
  void afterLastSeq_isEmpty() {
    MessagePage page = service.list(member.getId(), general, 7L, null, 50);

    assertThat(page.messages()).isEmpty();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void invalidParameters_are400() {
    assertThatThrownBy(() -> service.list(member.getId(), general, 1L, 5L, 10))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.list(member.getId(), general, -1L, null, 10))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.list(member.getId(), general, null, 0L, 10))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.list(member.getId(), general, null, null, 0))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.list(member.getId(), general, null, null, 101))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void hiddenChannel_is404_beforeLookingAtParameters() {
    // 404 aunque los parámetros también estén mal: no se confirma que el canal existe.
    assertThatThrownBy(() -> service.list(member.getId(), secret, 1L, 5L, 0))
        .isInstanceOf(NotFoundException.class);
  }
}
