package com.jsav.chat.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestUsers;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.search.SearchDtos.SearchPage;
import com.jsav.chat.search.SearchDtos.SearchResult;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Qué encuentra la búsqueda y cómo pagina (ADR 0016), con un solo canal y su owner. Quién ve qué
 * está en {@link SearchAccessIntegrationTest}. Postgres real: la derivación y las palabras vacías
 * son del diccionario {@code spanish}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired SearchService service;
  @Autowired MessageService messages;
  @Autowired UserRepository users;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;

  User owner;
  Long serverId;
  Long general;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(TestUsers.user("se" + run + "@x.test", "x", "Ana Pérez"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Se" + run)).id();
    general = channelService.list(owner.getId(), serverId).get(0).id();
  }

  private long send(String body) {
    return messages
        .send(owner.getId(), general, new SendMessageRequest(UUID.randomUUID(), body))
        .message()
        .id();
  }

  private SearchPage search(String q) {
    return service.search(owner.getId(), serverId, q, null, null, SearchService.DEFAULT_LIMIT);
  }

  private static List<Long> ids(SearchPage page) {
    return page.results().stream().map(SearchResult::messageId).toList();
  }

  @Test
  void findsAWord_andSaysWhereItIs() {
    send("nada que ver");
    long id = send("mañana hay reunión del equipo");

    SearchPage page = search("reunión");

    assertThat(ids(page)).containsExactly(id);
    SearchResult r = page.results().get(0);
    assertThat(r.channelId()).isEqualTo(general);
    assertThat(r.channelName()).isEqualTo("general");
    assertThat(r.seq()).isEqualTo(2);
    assertThat(r.authorName()).isEqualTo("Ana Pérez");
    assertThat(r.createdAt()).isNotNull();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void spanishStemming_matchesOtherFormsOfTheWord() {
    long plural = send("revisé los mensajes de ayer");
    long singular = send("te dejo un mensaje");

    assertThat(ids(search("mensaje"))).containsExactly(singular, plural);
    assertThat(ids(search("mensajes"))).containsExactly(singular, plural);
  }

  @Test
  void allWordsMustAppear_unlessYouSayOr() {
    long both = send("el gato y el perro");
    long cat = send("solo el gato");

    assertThat(ids(search("gato perro"))).containsExactly(both);
    assertThat(ids(search("gato or perro"))).containsExactly(cat, both);
    assertThat(ids(search("gato -perro"))).containsExactly(cat);
  }

  @Test
  void onlyStopWords_findNothing() {
    send("de la casa");

    assertThat(search("de la").results()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"\"gato", "gato & | ! ( :*", "'; drop table messages; --", "-", "or", "\\"})
  void oddInput_isNotAnError(String q) {
    send("el gato");

    SearchPage page = search(q);

    assertThat(page)
        .isNotNull(); // sin excepción: websearch_to_tsquery no tiene errores de sintaxis
  }

  @Test
  void newestFirst_andTheCursorWalksEverythingOnce() {
    List<Long> sent = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      sent.add(send("pedido número " + i));
    }
    send("otra cosa");

    List<Long> seen = new ArrayList<>();
    Long before = null;
    List<Boolean> more = new ArrayList<>();
    for (int guard = 0; guard < 10; guard++) {
      SearchPage page = service.search(owner.getId(), serverId, "pedido", null, before, 3);
      seen.addAll(ids(page));
      more.add(page.hasMore());
      if (!page.hasMore()) break;
      before = page.results().get(page.results().size() - 1).messageId();
    }

    assertThat(seen).containsExactlyElementsOf(sent.reversed());
    assertThat(more).containsExactly(true, true, false);
  }

  @Test
  void theSnippetMarksTheWordsFound() {
    send("la reunión de mañana se pasa a las cinco");

    String snippet = search("reunión").results().get(0).snippet();

    assertThat(snippet)
        .contains(SearchRepository.MARK_START + "reunión" + SearchRepository.MARK_END)
        .doesNotContain("<b>");
  }

  @Test
  void markersTypedByTheAuthor_cannotFakeAHighlight() {
    String fake = SearchRepository.MARK_START + "falso" + SearchRepository.MARK_END;
    send(fake + " pero el gato es real");

    String snippet = search("gato").results().get(0).snippet();

    assertThat(snippet)
        .contains("falso")
        .doesNotContain(fake)
        .contains(SearchRepository.MARK_START + "gato" + SearchRepository.MARK_END);
  }

  @Test
  void theQueryIsTrimmed() {
    long id = send("el gato");

    assertThat(ids(search("   gato  "))).containsExactly(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void anEmptyQuery_is400(String q) {
    assertThatThrownBy(() -> search(q)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void aMissingQuery_is400() {
    assertThatThrownBy(() -> search(null)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void aQueryOver200Characters_is400_butExactly200IsFine() {
    assertThatThrownBy(() -> search("a".repeat(201))).isInstanceOf(BadRequestException.class);
    assertThat(search("a".repeat(200)).results()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 51})
  void aLimitOutOfRange_is400(int limit) {
    assertThatThrownBy(() -> service.search(owner.getId(), serverId, "gato", null, null, limit))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void aBeforeIdBelowOne_is400() {
    assertThatThrownBy(() -> service.search(owner.getId(), serverId, "gato", null, 0L, 10))
        .isInstanceOf(BadRequestException.class);
  }
}
