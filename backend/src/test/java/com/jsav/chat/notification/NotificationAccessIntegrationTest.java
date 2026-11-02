package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.notification.NotificationDtos.NotificationItem;
import com.jsav.chat.notification.NotificationDtos.NotificationList;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.ChangeRoleRequest;
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

/**
 * Qué ve cada persona de sus notificaciones (ADR 0011). Una notificación sobrevive a los cambios de
 * permisos, pero la lista no: se filtra contra el acceso de ahora, así que quien pierde acceso a un
 * canal deja de ver sus menciones, y nadie toca las de otra persona. Las notificaciones se siembran
 * directo en la tabla: lo que se prueba aquí es la lectura, no el worker.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationAccessIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired NotificationService service;
  @Autowired NotificationRepository plant;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  User owner;
  User admin;
  User ana;
  User beto;
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("na" + run + "-o@x.test", "x", "Owner", "no" + run + "_o"));
    admin = users.save(new User("na" + run + "-a@x.test", "x", "Admin", "no" + run + "_a"));
    ana = users.save(new User("na" + run + "-n@x.test", "x", "Ana", "no" + run + "_n"));
    beto = users.save(new User("na" + run + "-b@x.test", "x", "Beto", "no" + run + "_b"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Not" + run)).id();
    serverMembers.save(new ServerMember(serverId, admin.getId(), Role.ADMIN));
    serverMembers.save(new ServerMember(serverId, ana.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, beto.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    // Ana está en el privado; beto no. El admin lo ve por ser admin.
    channelService.addMember(owner.getId(), secret, ana.getId());

    long inGeneral = message(general, "mensaje en general");
    long inSecret = message(secret, "mensaje secreto");
    plant.insertIfAbsent(inGeneral, ana.getId(), general);
    plant.insertIfAbsent(inSecret, ana.getId(), secret);
    plant.insertIfAbsent(inSecret, admin.getId(), secret);
  }

  private long message(Long channelId, String body) {
    return messages
        .send(owner.getId(), channelId, new SendMessageRequest(UUID.randomUUID(), body))
        .message()
        .id();
  }

  private NotificationList listOf(User who) {
    return service.list(who.getId(), NotificationService.DEFAULT_LIMIT);
  }

  @Test
  void aPersonSeesTheirMentions_newestFirst_withTheirContext() {
    NotificationList list = listOf(ana);

    assertThat(list.unreadCount()).isEqualTo(2);
    assertThat(list.items()).hasSize(2);
    NotificationItem newest = list.items().get(0);
    assertThat(newest.channelName()).isEqualTo("secreto");
    assertThat(newest.authorName()).isEqualTo("Owner");
    assertThat(newest.snippet()).isEqualTo("mensaje secreto");
    assertThat(newest.serverId()).isEqualTo(serverId);
    assertThat(newest.read()).isFalse();
    assertThat(list.items().get(1).snippet()).isEqualTo("mensaje en general");
  }

  @Test
  void aPersonWithoutMentions_getsAnEmptyList() {
    NotificationList list = listOf(beto);

    assertThat(list.unreadCount()).isZero();
    assertThat(list.items()).isEmpty();
  }

  @Test
  void aKickedMember_stopsSeeingTheMentionsOfThatServer() {
    serverService.kick(owner.getId(), serverId, ana.getId());

    NotificationList list = listOf(ana);

    assertThat(list.unreadCount()).isZero();
    assertThat(list.items()).isEmpty();
  }

  @Test
  void takenOutOfAPrivateChannel_losesOnlyThoseOfThatChannel() {
    channelService.removeMember(owner.getId(), secret, ana.getId());

    NotificationList list = listOf(ana);

    assertThat(list.unreadCount()).isEqualTo(1);
    assertThat(list.items())
        .extracting(NotificationItem::snippet)
        .containsExactly("mensaje en general");
  }

  @Test
  void anAdminDemotedToMember_losesThePrivateChannelHeOnlySawAsAdmin() {
    assertThat(listOf(admin).items()).hasSize(1);

    serverService.changeRole(
        owner.getId(), serverId, admin.getId(), new ChangeRoleRequest("member"));

    assertThat(listOf(admin).items()).isEmpty();
    assertThat(listOf(admin).unreadCount()).isZero();
  }

  @Test
  void markingOneAsRead_changesItAndTheCount() {
    long id = listOf(ana).items().get(0).id();

    service.markRead(ana.getId(), id);

    NotificationList list = listOf(ana);
    assertThat(list.unreadCount()).isEqualTo(1);
    assertThat(list.items().get(0).read()).isTrue();
  }

  @Test
  void markingSomeoneElsesNotification_is404_andLeavesItUnread() {
    long anas = listOf(ana).items().get(0).id();

    assertThatThrownBy(() -> service.markRead(beto.getId(), anas))
        .isInstanceOf(NotFoundException.class);

    assertThat(listOf(ana).unreadCount()).isEqualTo(2);
  }

  @Test
  void markingAnUnknownNotification_is404_justLikeSomeoneElses() {
    assertThatThrownBy(() -> service.markRead(ana.getId(), 987_654_321L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void markAllAsRead_onlyTouchesYourOwn() {
    service.markAllRead(ana.getId());

    assertThat(listOf(ana).unreadCount()).isZero();
    assertThat(listOf(admin).unreadCount()).isEqualTo(1);
  }

  @Test
  void theUnreadCount_isNotCutByTheLimit() {
    NotificationList list = service.list(ana.getId(), 1);

    assertThat(list.items()).hasSize(1);
    assertThat(list.unreadCount()).isEqualTo(2);
  }

  @Test
  void aLimitOutOfRange_is400() {
    assertThatThrownBy(() -> service.list(ana.getId(), 0)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.list(ana.getId(), NotificationService.MAX_LIMIT + 1))
        .isInstanceOf(BadRequestException.class);
  }
}
