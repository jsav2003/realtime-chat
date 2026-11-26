package com.jsav.chat.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.search.SearchDtos.SearchPage;
import com.jsav.chat.search.SearchDtos.SearchResult;
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

/**
 * La búsqueda nunca deja ver un mensaje de un canal que no puedes leer (ADR 0016): la misma palabra
 * en {@code #general} y en un privado donde Beto no está. Postgres real.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchAccessIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired SearchService service;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  User owner;
  User admin;
  User ana; // está en el privado
  User beto; // no está en el privado
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("sa" + run + "-o@x.test", "x", "Owner"));
    admin = users.save(TestUsers.user("sa" + run + "-d@x.test", "x", "Admin"));
    ana = users.save(TestUsers.user("sa" + run + "-a@x.test", "x", "Ana"));
    beto = users.save(TestUsers.user("sa" + run + "-b@x.test", "x", "Beto"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Sa" + run)).id();
    serverMembers.save(new ServerMember(serverId, admin.getId(), Role.ADMIN));
    serverMembers.save(new ServerMember(serverId, ana.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, beto.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
    channelService.addMember(owner.getId(), secret, ana.getId());
  }

  private long send(Long channelId, String body) {
    return messages
        .send(owner.getId(), channelId, new SendMessageRequest(UUID.randomUUID(), body))
        .message()
        .id();
  }

  private SearchPage search(User who, String q, Long channelId, int limit) {
    return service.search(who.getId(), serverId, q, channelId, null, limit);
  }

  private List<Long> ids(User who, String q) {
    return search(who, q, null, SearchService.DEFAULT_LIMIT).results().stream()
        .map(SearchResult::messageId)
        .toList();
  }

  @Test
  void aPrivateChannelYouAreNotIn_neverShowsUp() {
    long open = send(general, "el presupuesto del viaje");
    long hidden = send(secret, "el presupuesto secreto");

    assertThat(ids(beto, "presupuesto")).containsExactly(open);
    assertThat(ids(ana, "presupuesto")).containsExactly(hidden, open);
    assertThat(ids(owner, "presupuesto")).containsExactly(hidden, open);
    assertThat(ids(admin, "presupuesto"))
        .containsExactly(hidden, open); // administra: ve los privados
  }

  /**
   * El caso que delata filtrar después del límite: lo más nuevo que coincide está en el privado.
   * Con el filtro en el {@code WHERE}, Beto recibe el de {@code #general} y nada más.
   */
  @Test
  void withLimitOne_andTheNewestMatchHidden_thePageIsStillFull() {
    long open = send(general, "presupuesto viejo");
    send(secret, "presupuesto nuevo");
    send(secret, "presupuesto más nuevo");

    SearchPage page = search(beto, "presupuesto", null, 1);

    assertThat(page.results()).extracting(SearchResult::messageId).containsExactly(open);
    assertThat(page.hasMore()).isFalse(); // no deja adivinar que hay más en lo oculto
  }

  @Test
  void takenOutOfThePrivateChannel_itsMessagesDisappearOnTheNextSearch() {
    long hidden = send(secret, "la clave del wifi");
    assertThat(ids(ana, "clave")).containsExactly(hidden);

    channelService.removeMember(owner.getId(), secret, ana.getId());

    assertThat(ids(ana, "clave")).isEmpty();
  }

  @Test
  void aKickedMember_getsNotFound() {
    send(general, "hola a todos");
    serverService.kick(owner.getId(), serverId, beto.getId());

    assertThatThrownBy(() -> ids(beto, "hola")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aStranger_getsNotFound() {
    User outsider = users.save(TestUsers.user("sa-out" + serverId + "@x.test", "x", "Out"));
    send(general, "hola a todos");

    assertThatThrownBy(() -> ids(outsider, "hola")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void filteringByChannel_onlySearchesThatChannel() {
    long open = send(general, "agenda de la semana");
    send(secret, "agenda privada");

    SearchPage page = search(ana, "agenda", general, 20);

    assertThat(page.results()).extracting(SearchResult::messageId).containsExactly(open);
  }

  @Test
  void filteringByAPrivateChannelYouAreNotIn_isNotFound() {
    send(secret, "agenda privada");

    assertThatThrownBy(() -> search(beto, "agenda", secret, 20))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void filteringByAChannelOfAnotherServer_isNotFound() {
    Long otherServer =
        serverService.create(beto.getId(), new CreateServerRequest("Otro" + serverId)).id();
    Long otherGeneral = channelService.list(beto.getId(), otherServer).get(0).id();
    messages.send(
        beto.getId(), otherGeneral, new SendMessageRequest(UUID.randomUUID(), "agenda ajena"));

    // Beto puede leer ese canal, pero no es de este servidor.
    assertThatThrownBy(() -> search(beto, "agenda", otherGeneral, 20))
        .isInstanceOf(NotFoundException.class);
    // Y buscar en este servidor no trae lo del otro.
    assertThat(ids(beto, "agenda")).isEmpty();
  }

  @Test
  void filteringByAChannelThatDoesNotExist_isNotFound() {
    assertThatThrownBy(() -> search(beto, "agenda", 987_654_321L, 20))
        .isInstanceOf(NotFoundException.class);
  }
}
