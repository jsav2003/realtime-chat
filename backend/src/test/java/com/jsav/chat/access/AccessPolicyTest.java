package com.jsav.chat.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.jsav.chat.channel.Channel;
import com.jsav.chat.channel.ChannelKind;
import com.jsav.chat.channel.ChannelMemberId;
import com.jsav.chat.channel.ChannelMemberRepository;
import com.jsav.chat.channel.ChannelRepository;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberId;
import com.jsav.chat.server.ServerMemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessPolicyTest {

  static final long SERVER = 1L;
  static final long OWNER = 10L;
  static final long ADMIN = 11L;
  static final long ADMIN_2 = 12L;
  static final long MEMBER = 13L;
  static final long OUTSIDER = 99L;

  @Mock ServerMemberRepository members;
  @Mock ChannelRepository channels;
  @Mock ChannelMemberRepository channelMembers;
  @InjectMocks AccessPolicy policy;

  private void member(long userId, Role role) {
    lenient()
        .when(members.findById(new ServerMemberId(SERVER, userId)))
        .thenReturn(Optional.of(new ServerMember(SERVER, userId, role)));
  }

  private void roster() {
    member(OWNER, Role.OWNER);
    member(ADMIN, Role.ADMIN);
    member(ADMIN_2, Role.ADMIN);
    member(MEMBER, Role.MEMBER);
  }

  private void channel(long id, ChannelKind kind) {
    Channel c = org.mockito.Mockito.mock(Channel.class);
    lenient().when(c.getServerId()).thenReturn(SERVER);
    lenient().when(c.kind()).thenReturn(kind);
    lenient().when(channels.findById(id)).thenReturn(Optional.of(c));
  }

  // ---- servidor ----

  @Test
  void requireServer_nonMemberGets404_notForbidden() {
    roster();

    assertThatThrownBy(() -> policy.requireServer(OUTSIDER, SERVER, ServerAction.VIEW))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireServer_unknownServerGets404() {
    assertThatThrownBy(() -> policy.requireServer(OWNER, 555L, ServerAction.VIEW))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireServer_everyMemberCanView() {
    roster();

    assertThat(policy.requireServer(MEMBER, SERVER, ServerAction.VIEW)).isEqualTo(Role.MEMBER);
    assertThat(policy.requireServer(ADMIN, SERVER, ServerAction.VIEW)).isEqualTo(Role.ADMIN);
    assertThat(policy.requireServer(OWNER, SERVER, ServerAction.VIEW)).isEqualTo(Role.OWNER);
  }

  @Test
  void requireServer_adminActionsNeedOwnerOrAdmin() {
    roster();

    for (ServerAction a :
        new ServerAction[] {
          ServerAction.MANAGE_CHANNELS, ServerAction.CREATE_INVITE, ServerAction.KICK
        }) {
      assertThat(policy.requireServer(OWNER, SERVER, a)).isEqualTo(Role.OWNER);
      assertThat(policy.requireServer(ADMIN, SERVER, a)).isEqualTo(Role.ADMIN);
      assertThatThrownBy(() -> policy.requireServer(MEMBER, SERVER, a))
          .as("member intentando %s", a)
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Test
  void requireServer_changeRoleIsOwnerOnly() {
    roster();

    assertThat(policy.requireServer(OWNER, SERVER, ServerAction.CHANGE_ROLE)).isEqualTo(Role.OWNER);
    assertThatThrownBy(() -> policy.requireServer(ADMIN, SERVER, ServerAction.CHANGE_ROLE))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.requireServer(MEMBER, SERVER, ServerAction.CHANGE_ROLE))
        .isInstanceOf(ForbiddenException.class);
  }

  // ---- actuar sobre otro miembro ----

  @Test
  void requireCanActOn_ownerCanKickAdminAndMember() {
    roster();

    assertThat(policy.requireCanActOn(OWNER, SERVER, ADMIN, ServerAction.KICK)).isNotNull();
    assertThat(policy.requireCanActOn(OWNER, SERVER, MEMBER, ServerAction.KICK)).isNotNull();
  }

  @Test
  void requireCanActOn_adminCanKickMemberButNotAdmin() {
    roster();

    assertThat(policy.requireCanActOn(ADMIN, SERVER, MEMBER, ServerAction.KICK)).isNotNull();
    assertThatThrownBy(() -> policy.requireCanActOn(ADMIN, SERVER, ADMIN_2, ServerAction.KICK))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void requireCanActOn_ownerIsUntouchable() {
    roster();

    assertThatThrownBy(() -> policy.requireCanActOn(ADMIN, SERVER, OWNER, ServerAction.KICK))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.requireCanActOn(OWNER, SERVER, OWNER, ServerAction.CHANGE_ROLE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void requireCanActOn_nobodyActsOnThemselves() {
    roster();

    assertThatThrownBy(() -> policy.requireCanActOn(ADMIN, SERVER, ADMIN, ServerAction.KICK))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void requireCanActOn_memberCannotKick_andTargetOutsideServerIs404() {
    roster();

    assertThatThrownBy(() -> policy.requireCanActOn(MEMBER, SERVER, ADMIN, ServerAction.KICK))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.requireCanActOn(OWNER, SERVER, OUTSIDER, ServerAction.KICK))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireCanActOn_outsiderActorGets404() {
    roster();

    assertThatThrownBy(() -> policy.requireCanActOn(OUTSIDER, SERVER, MEMBER, ServerAction.KICK))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- canales ----

  @Test
  void requireChannel_unknownChannelIs404() {
    assertThatThrownBy(() -> policy.requireChannel(OWNER, 404L, ChannelAction.READ))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireChannel_textChannelReadableByAnyServerMember() {
    roster();
    channel(100L, ChannelKind.TEXT);

    assertThat(policy.requireChannel(MEMBER, 100L, ChannelAction.READ).role())
        .isEqualTo(Role.MEMBER);
  }

  @Test
  void requireChannel_nonServerMemberGets404EvenOnTextChannel() {
    roster();
    channel(100L, ChannelKind.TEXT);

    assertThatThrownBy(() -> policy.requireChannel(OUTSIDER, 100L, ChannelAction.READ))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireChannel_privateIsHiddenFromMemberNotInChannel() {
    roster();
    channel(200L, ChannelKind.PRIVATE);
    when(channelMembers.existsById(new ChannelMemberId(200L, MEMBER))).thenReturn(false);

    assertThatThrownBy(() -> policy.requireChannel(MEMBER, 200L, ChannelAction.READ))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireChannel_privateVisibleToMemberInChannel() {
    roster();
    channel(200L, ChannelKind.PRIVATE);
    when(channelMembers.existsById(new ChannelMemberId(200L, MEMBER))).thenReturn(true);

    assertThat(policy.requireChannel(MEMBER, 200L, ChannelAction.READ)).isNotNull();
  }

  @Test
  void requireChannel_ownerAndAdminSeeEveryPrivateChannel() {
    roster();
    channel(200L, ChannelKind.PRIVATE);

    assertThat(policy.requireChannel(OWNER, 200L, ChannelAction.READ)).isNotNull();
    assertThat(policy.requireChannel(ADMIN, 200L, ChannelAction.READ)).isNotNull();
  }

  @Test
  void requireChannel_manageMembersIsAdminOnly_andMemberInsideGets403() {
    roster();
    channel(200L, ChannelKind.PRIVATE);
    when(channelMembers.existsById(new ChannelMemberId(200L, MEMBER))).thenReturn(true);

    assertThat(policy.requireChannel(ADMIN, 200L, ChannelAction.MANAGE_MEMBERS)).isNotNull();
    // Lo ve, por eso 403 y no 404.
    assertThatThrownBy(() -> policy.requireChannel(MEMBER, 200L, ChannelAction.MANAGE_MEMBERS))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void requireChannel_dmOnlyForItsMembers() {
    channel(300L, ChannelKind.DM);
    when(channelMembers.existsById(new ChannelMemberId(300L, MEMBER))).thenReturn(true);
    when(channelMembers.existsById(new ChannelMemberId(300L, OUTSIDER))).thenReturn(false);

    assertThat(policy.requireChannel(MEMBER, 300L, ChannelAction.READ)).isNotNull();
    assertThatThrownBy(() -> policy.requireChannel(OUTSIDER, 300L, ChannelAction.READ))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireChannel_writeFollowsVisibility_anyRoleThatSeesCanWrite() {
    roster();
    channel(100L, ChannelKind.TEXT);
    channel(200L, ChannelKind.PRIVATE);
    when(channelMembers.existsById(new ChannelMemberId(200L, MEMBER))).thenReturn(false);

    assertThat(policy.requireChannel(MEMBER, 100L, ChannelAction.WRITE).role())
        .isEqualTo(Role.MEMBER);
    assertThat(policy.requireChannel(ADMIN, 200L, ChannelAction.WRITE)).isNotNull();
    // No lo ve: 404, igual que al leer.
    assertThatThrownBy(() -> policy.requireChannel(MEMBER, 200L, ChannelAction.WRITE))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> policy.requireChannel(OUTSIDER, 100L, ChannelAction.WRITE))
        .isInstanceOf(NotFoundException.class);
  }
}
