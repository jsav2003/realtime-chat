package com.jsav.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** La presencia de un servidor con Postgres y Redis reales: solo miembros, solo los conectados. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PresenceServiceIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();
  private static final Duration LONG = Duration.ofMinutes(5);

  @Autowired PresenceService service;
  @Autowired PresenceStore store;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;

  User owner;
  User member;
  User outsider;
  Long serverId;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("pr" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(new User("pr" + run + "-member@x.test", "x", "Member"));
    outsider = users.save(new User("pr" + run + "-out@x.test", "x", "Outsider"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Pr" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
  }

  @Test
  void devuelve_solo_a_los_miembros_conectados() {
    store.touch(member.getId(), "a:1", LONG);

    assertThat(service.online(owner.getId(), serverId)).containsExactly(member.getId());
  }

  @Test
  void nadie_conectado_da_lista_vacia() {
    assertThat(service.online(owner.getId(), serverId)).isEmpty();
  }

  @Test
  void no_revela_si_alguien_de_fuera_esta_en_linea() {
    // El outsider está conectado, pero no es miembro de este servidor.
    store.touch(outsider.getId(), "a:1", LONG);
    store.touch(member.getId(), "a:2", LONG);

    assertThat(service.online(owner.getId(), serverId)).containsExactly(member.getId());
  }

  @Test
  void quien_no_es_miembro_recibe_404() {
    assertThatThrownBy(() -> service.online(outsider.getId(), serverId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void un_miembro_expulsado_deja_de_aparecer() {
    store.touch(member.getId(), "a:1", LONG);

    serverService.kick(owner.getId(), serverId, member.getId());

    // La presencia sigue en Redis (su socket aún no se cerró en este test), pero ya no es miembro.
    assertThat(service.online(owner.getId(), serverId)).isEmpty();
  }
}
