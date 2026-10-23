package com.jsav.chat.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ServerServiceTest {

  @Mock ServerRepository servers;
  @Mock ServerMemberRepository members;
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
  void listMine_delegatesToMembershipQuery() {
    when(servers.findByMember(7L)).thenReturn(List.of(new ChatServer("A", 7L)));

    assertThat(service.listMine(7L)).extracting(ServerResponse::name).containsExactly("A");
  }
}
