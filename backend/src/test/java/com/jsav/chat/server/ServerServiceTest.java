package com.jsav.chat.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.channel.Channel;
import com.jsav.chat.channel.ChannelKind;
import com.jsav.chat.channel.ChannelMemberRepository;
import com.jsav.chat.channel.ChannelRepository;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ServerServiceTest {

  @Mock ServerRepository servers;
  @Mock ServerMemberRepository members;
  @Mock ChannelRepository channels;
  @Mock ChannelMemberRepository channelMembers;
  @Mock AccessPolicy policy;
  @Mock ApplicationEventPublisher events;
  @InjectMocks ServerService service;

  @Test
  void create_savesCreatorAsOwner() {
    when(servers.save(any())).thenAnswer(i -> i.getArgument(0));

    ServerResponse res = service.create(7L, new CreateServerRequest("  Mi servidor "));

    assertThat(res.name()).isEqualTo("Mi servidor");
    assertThat(res.ownerId()).isEqualTo(7L);
    ArgumentCaptor<ServerMember> member = ArgumentCaptor.forClass(ServerMember.class);
    verify(members).save(member.capture());
    assertThat(member.getValue().getRole()).isEqualTo("owner");
    assertThat(member.getValue().getId().getUserId()).isEqualTo(7L);
  }

  @Test
  void create_alsoCreatesGeneralTextChannel() {
    when(servers.save(any())).thenAnswer(i -> i.getArgument(0));

    service.create(7L, new CreateServerRequest("Mi servidor"));

    ArgumentCaptor<Channel> channel = ArgumentCaptor.forClass(Channel.class);
    verify(channels).save(channel.capture());
    assertThat(channel.getValue().getName()).isEqualTo("general");
    assertThat(channel.getValue().kind()).isEqualTo(ChannelKind.TEXT);
  }

  @Test
  void listMine_delegatesToMembershipQuery() {
    when(servers.findByMember(7L)).thenReturn(List.of(new ChatServer("A", 7L)));

    assertThat(service.listMine(7L)).extracting(ServerResponse::name).containsExactly("A");
  }

  @Test
  void kick_publishesUserKicked_afterRemovingTheMember() {
    service.kick(1L, 10L, 7L);

    verify(channelMembers).removeFromServer(10L, 7L);
    verify(members).deleteMember(10L, 7L);
    verify(events).publishEvent(new UserKicked(10L, 7L));
  }

  @Test
  void kick_publishesNothing_whenThePolicyRefuses() {
    when(policy.requireCanActOn(1L, 10L, 7L, ServerAction.KICK))
        .thenThrow(new ForbiddenException("no"));

    assertThatThrownBy(() -> service.kick(1L, 10L, 7L)).isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(events);
  }
}
