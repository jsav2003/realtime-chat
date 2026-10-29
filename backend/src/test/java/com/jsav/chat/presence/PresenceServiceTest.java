package com.jsav.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.ServiceUnavailableException;
import com.jsav.chat.server.ServerMemberRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class PresenceServiceTest {

  private final AccessPolicy policy = mock(AccessPolicy.class);
  private final ServerMemberRepository members = mock(ServerMemberRepository.class);
  private final PresenceStore store = mock(PresenceStore.class);
  private final PresenceService service = new PresenceService(policy, members, store);

  @Test
  void devuelve_ordenados_a_los_miembros_en_linea() {
    when(members.findUserIds(3L)).thenReturn(List.of(9L, 4L, 7L));
    when(store.online(List.of(9L, 4L, 7L))).thenReturn(Set.of(9L, 4L));

    assertThat(service.online(4L, 3L)).containsExactly(4L, 9L);
    verify(policy).requireServer(4L, 3L, ServerAction.VIEW);
  }

  @Test
  void quien_no_es_miembro_recibe_404_y_no_se_consulta_redis() {
    when(policy.requireServer(8L, 3L, ServerAction.VIEW))
        .thenThrow(new NotFoundException("Servidor no encontrado"));

    assertThatThrownBy(() -> service.online(8L, 3L)).isInstanceOf(NotFoundException.class);
    verify(store, never()).online(List.of());
  }

  @Test
  void sin_redis_responde_503() {
    when(members.findUserIds(3L)).thenReturn(List.of(4L));
    when(store.online(List.of(4L))).thenThrow(new RedisConnectionFailureException("caído"));

    assertThatThrownBy(() -> service.online(4L, 3L))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
