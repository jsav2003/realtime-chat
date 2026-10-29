package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.channel.ChannelDtos.ChannelResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.user.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;

/**
 * Matriz de autorización de STOMP (DESIGN.md §8.2): cada destino y comando contra cada tipo de
 * usuario, un test por celda y por un WebSocket real. Es la misma idea que la matriz HTTP de {@code
 * AuthorizationMatrixTest}, y comparte con ella los seis tipos de usuario.
 */
class StompAuthorizationMatrixTest extends RealtimeIntegrationTest {

  enum Actor {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    OTHER("miembro de otro servidor"),
    ANON("sin sesión"),
    KICKED("expulsado hace un segundo");

    final String label;

    Actor(String label) {
      this.label = label;
    }
  }

  /** Canales a los que apunta un destino. */
  enum Ref {
    GENERAL, // canal público
    PRIVATE, // privado donde member NO está
    PRIVATE_IN, // privado donde member SÍ está
    NONE // el destino se usa tal cual
  }

  /**
   * Una fila: un comando STOMP sobre un destino. {@code expected} va en el orden de {@link Actor};
   * "ok" es permitido, y el resto son los códigos del frame ERROR (los mismos que daría HTTP).
   */
  record Row(String command, String destination, String note, Ref ref, List<String> expected) {

    String key() {
      return command + " " + destination;
    }

    String resolve(World w) {
      return ref == Ref.NONE
          ? destination
          : destination.replace("{channelId}", String.valueOf(w.channels.get(ref)));
    }
  }

  private static Row row(
      String command,
      String destination,
      String note,
      Ref ref,
      String owner,
      String admin,
      String member,
      String other,
      String anon,
      String kicked) {
    return new Row(
        command, destination, note, ref, List.of(owner, admin, member, other, anon, kicked));
  }

  static final String CHANNEL_TOPIC = "/topic/channel.{channelId}";
  static final String TYPING_TOPIC = "/topic/channel.{channelId}.typing";

  static final List<Row> MATRIX =
      List.of(
          row(
              "SUBSCRIBE",
              CHANNEL_TOPIC,
              "público",
              Ref.GENERAL,
              "ok",
              "ok",
              "ok",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              CHANNEL_TOPIC,
              "privado, member no está",
              Ref.PRIVATE,
              "ok",
              "ok",
              "404",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              CHANNEL_TOPIC,
              "privado, member sí está",
              Ref.PRIVATE_IN,
              "ok",
              "ok",
              "ok",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              TYPING_TOPIC,
              "público",
              Ref.GENERAL,
              "ok",
              "ok",
              "ok",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              TYPING_TOPIC,
              "privado, member no está",
              Ref.PRIVATE,
              "ok",
              "ok",
              "404",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              TYPING_TOPIC,
              "privado, member sí está",
              Ref.PRIVATE_IN,
              "ok",
              "ok",
              "ok",
              "404",
              "401",
              "404"),
          row(
              "SEND",
              TYPING_TOPIC,
              "público",
              Ref.GENERAL,
              "403",
              "403",
              "403",
              "403",
              "401",
              "403"),
          row(
              "SUBSCRIBE",
              "/topic/channel.999999999",
              "canal que no existe",
              Ref.NONE,
              "404",
              "404",
              "404",
              "404",
              "401",
              "404"),
          row(
              "SUBSCRIBE",
              "/topic/other",
              "destino que no es de un canal",
              Ref.NONE,
              "404",
              "404",
              "404",
              "404",
              "401",
              "404"),
          row(
              "SEND",
              CHANNEL_TOPIC,
              "público",
              Ref.GENERAL,
              "403",
              "403",
              "403",
              "403",
              "401",
              "403"),
          row(
              "SEND",
              CHANNEL_TOPIC,
              "privado, member no está",
              Ref.PRIVATE,
              "403",
              "403",
              "403",
              "403",
              "401",
              "403"),
          row(
              "SEND",
              "/app/channels/1/messages",
              "destino de aplicación",
              Ref.NONE,
              "403",
              "403",
              "403",
              "403",
              "401",
              "403"));

  @Autowired SimpAnnotationMethodMessageHandler annotatedHandlers;

  /**
   * Si alguien agrega un {@code @MessageMapping} o un {@code @SubscribeMapping}, aparece un destino
   * de aplicación nuevo que esta matriz no cubre. Hoy no hay ninguno: se escribe solo por HTTP (ADR
   * 0006) y el único destino al que se suscribe es {@code /topic/channel.{id}}.
   */
  @Test
  void everyStompDestinationIsInTheMatrix() {
    assertThat(annotatedHandlers.getHandlerMethods())
        .as("hay destinos STOMP de aplicación sin fila en la matriz: agrégalos a MATRIX")
        .isEmpty();

    Set<String> covered = new TreeSet<>();
    MATRIX.forEach(r -> covered.add(r.key()));
    assertThat(covered)
        .as("el destino de suscripción real debe estar en la matriz, y SEND también")
        .contains(
            "SUBSCRIBE " + CHANNEL_TOPIC,
            "SEND " + CHANNEL_TOPIC,
            "SUBSCRIBE " + TYPING_TOPIC,
            "SEND " + TYPING_TOPIC);
    assertThat(ChannelTopic.of(1)).matches(CHANNEL_TOPIC.replace("{channelId}", "1"));
    assertThat(ChannelTopic.typingOf(1)).matches(TYPING_TOPIC.replace("{channelId}", "1"));
  }

  // ---- docs/AUTHZ.md es la misma tabla ----

  private static final String START = "<!-- stomp-matrix:start -->";
  private static final String END = "<!-- stomp-matrix:end -->";
  private static final Path DOC = Path.of("..", "docs", "AUTHZ.md");

  @Test
  void authzDocMatchesTheStompMatrix() throws IOException {
    String doc = Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
    int from = doc.indexOf(START);
    int to = doc.indexOf(END);
    assertThat(from).as("falta %s en docs/AUTHZ.md", START).isNotNegative();
    assertThat(to).as("falta %s en docs/AUTHZ.md", END).isGreaterThan(from);

    String generated = markdownTable();
    if (System.getProperty("authz.update") != null) {
      String updated =
          doc.substring(0, from + START.length()) + "\n" + generated + "\n" + doc.substring(to);
      Files.writeString(DOC, updated, StandardCharsets.UTF_8);
      return;
    }
    String current = doc.substring(from + START.length(), to).trim();
    assertThat(current)
        .as(
            "docs/AUTHZ.md desactualizado; regenerar con -Dauthz.update=true. Tabla esperada:\n%s",
            generated)
        .isEqualTo(generated.trim());
  }

  private static String markdownTable() {
    StringBuilder sb = new StringBuilder();
    sb.append("| Comando y destino | Caso");
    for (Actor a : Actor.values()) {
      sb.append(" | ").append(a.label);
    }
    sb.append(" |\n|---|---");
    sb.append("|---".repeat(Actor.values().length));
    sb.append("|");
    for (Row r : MATRIX) {
      sb.append("\n| `").append(r.key()).append("` | ").append(r.note());
      r.expected().forEach(code -> sb.append(" | ").append(code));
      sb.append(" |");
    }
    return sb.toString();
  }

  private static final AtomicInteger WORLDS = new AtomicInteger();

  /** Un escenario nuevo por celda: ninguna celda ve lo que cambió otra. */
  static final class World {
    final Map<Actor, String> tokens = new EnumMap<>(Actor.class);
    final Map<Ref, Long> channels = new EnumMap<>(Ref.class);
  }

  @TestFactory
  Stream<DynamicTest> matrix() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Row row : MATRIX) {
      for (Actor actor : Actor.values()) {
        String expected = row.expected().get(actor.ordinal());
        String name = "%s [%s] · %s → %s".formatted(row.key(), row.note(), actor.label, expected);
        tests.add(DynamicTest.dynamicTest(name, () -> check(row, actor, expected)));
      }
    }
    return tests.stream();
  }

  private void check(Row row, Actor actor, String expected) {
    World w = newWorld();
    assertThat(run(row, actor, w)).isEqualTo(expected);
  }

  /** "ok" si el servidor lo dejó pasar; si no, el código del frame ERROR. */
  private String run(Row row, Actor actor, World w) {
    String token = w.tokens.get(actor);
    if (token == null) {
      return codeOf(StompTestClient.connectError(port, null));
    }
    try (StompTestClient client = StompTestClient.connect(port, token)) {
      String destination = row.resolve(w);
      String error;
      if (row.command().equals("SUBSCRIBE")) {
        error = client.subscribe(destination);
      } else {
        client.send(destination, "{\"seq\":999}");
        error = client.pollError(Duration.ofSeconds(5));
      }
      return error == null ? "ok" : codeOf(error);
    }
  }

  private static String codeOf(String error) {
    return error == null ? "ok" : error.substring(0, Math.min(3, error.length()));
  }

  private User user(String email, String name) {
    return users.save(new User(email, "no-login-needed", name));
  }

  private World newWorld() {
    int n = WORLDS.incrementAndGet();
    String domain = "@stompmatrix" + n + ".test";
    User owner = user("owner" + domain, "Owner");
    User admin = user("admin" + domain, "Admin");
    User member = user("member" + domain, "Member");
    User other = user("other" + domain, "Other");
    User kicked = user("kicked" + domain, "Kicked");

    Long s = serverService.create(owner.getId(), new CreateServerRequest("Sm" + n)).id();
    serverMembers.save(new ServerMember(s, admin.getId(), Role.ADMIN));
    serverMembers.save(new ServerMember(s, member.getId(), Role.MEMBER));
    serverMembers.save(new ServerMember(s, kicked.getId(), Role.MEMBER));
    serverService.create(other.getId(), new CreateServerRequest("Ajeno" + n));

    ChannelResponse general = channelService.list(owner.getId(), s).get(0);
    ChannelResponse priv =
        channelService.create(owner.getId(), s, new CreateChannelRequest("secreto", "private"));
    ChannelResponse privIn =
        channelService.create(owner.getId(), s, new CreateChannelRequest("equipo", "private"));
    channelService.addMember(owner.getId(), privIn.id(), member.getId());
    channelService.addMember(owner.getId(), privIn.id(), kicked.getId());

    // Con el servicio real, justo antes de conectar: el token sigue vigente, la membresía no.
    String kickedToken = token(kicked);
    serverService.kick(owner.getId(), s, kicked.getId());

    World w = new World();
    w.tokens.put(Actor.OWNER, token(owner));
    w.tokens.put(Actor.ADMIN, token(admin));
    w.tokens.put(Actor.MEMBER, token(member));
    w.tokens.put(Actor.OTHER, token(other));
    w.tokens.put(Actor.KICKED, kickedToken);
    w.channels.put(Ref.GENERAL, general.id());
    w.channels.put(Ref.PRIVATE, priv.id());
    w.channels.put(Ref.PRIVATE_IN, privIn.id());
    return w;
  }
}
