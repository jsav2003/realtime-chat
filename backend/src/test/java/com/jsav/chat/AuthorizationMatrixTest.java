package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.auth.JwtService;
import com.jsav.chat.channel.ChannelDtos.ChannelResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.invite.InviteDtos.CreateInviteRequest;
import com.jsav.chat.invite.InviteDtos.InviteResponse;
import com.jsav.chat.invite.InviteService;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Matriz de autorización HTTP (DESIGN.md §8.2): cada endpoint contra cada tipo de usuario, un test
 * por celda. La misma tabla se verifica contra {@code docs/AUTHZ.md}; para regenerar el documento
 * después de cambiar la matriz: {@code ./mvnw test -Dtest=AuthorizationMatrixTest
 * -Dauthz.update=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthorizationMatrixTest {

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

  /** Cosas que un endpoint puede necesitar en su ruta o en su cuerpo. */
  enum Ref {
    SERVER,
    GENERAL, // canal público
    PRIVATE, // privado donde member NO está
    PRIVATE_IN, // privado donde member SÍ está
    VICTIM, // un member cualquiera, objetivo de expulsión o cambio de rol
    OWNER_ID,
    NEWCOMER, // miembro del servidor que no está en ningún privado
    CODE
  }

  /** Un escenario nuevo por celda: ningún test ve lo que cambió otro. */
  static final class World {
    final Map<Actor, String> tokens = new EnumMap<>(Actor.class);
    final Map<Ref, String> refs = new EnumMap<>(Ref.class);
  }

  record Row(
      String method,
      String pattern,
      String note,
      Map<String, Ref> vars,
      Function<World, String> body,
      List<Integer> expected) {

    String path(World w) {
      String path = pattern;
      for (Map.Entry<String, Ref> v : vars.entrySet()) {
        path = path.replace("{" + v.getKey() + "}", w.refs.get(v.getValue()));
      }
      return path;
    }

    String key() {
      return method + " " + pattern;
    }
  }

  private static Row row(
      String method,
      String pattern,
      String note,
      Map<String, Ref> vars,
      Function<World, String> body,
      int owner,
      int admin,
      int member,
      int other,
      int anon,
      int kicked) {
    return new Row(
        method, pattern, note, vars, body, List.of(owner, admin, member, other, anon, kicked));
  }

  private static final Map<String, Ref> NONE = Map.of();
  private static final Map<String, Ref> SERVER = Map.of("serverId", Ref.SERVER);
  private static final String JSON_OBJ = "{}";

  /** Cada envío lleva su propia clave para que ninguno cuente como reintento de otro. */
  private static final String MESSAGE_BODY_TEMPLATE = "{\"clientMsgId\":\"%s\",\"body\":\"hola\"}";

  static final List<Row> MATRIX =
      List.of(
          // ---- servidores ----
          row("GET", "/servers", "—", NONE, null, 200, 200, 200, 200, 401, 200),
          row(
              "POST",
              "/servers",
              "—",
              NONE,
              w -> "{\"name\":\"Nuevo\"}",
              201,
              201,
              201,
              201,
              401,
              201),
          row("GET", "/servers/{serverId}", "—", SERVER, null, 200, 200, 200, 404, 401, 404),
          row(
              "GET",
              "/servers/{serverId}/members",
              "—",
              SERVER,
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          row(
              "PUT",
              "/servers/{serverId}/members/{userId}/role",
              "cambiar a un member",
              Map.of("serverId", Ref.SERVER, "userId", Ref.VICTIM),
              w -> "{\"role\":\"admin\"}",
              200,
              403,
              403,
              404,
              401,
              404),
          row(
              "DELETE",
              "/servers/{serverId}/members/{userId}",
              "expulsar a un member",
              Map.of("serverId", Ref.SERVER, "userId", Ref.VICTIM),
              null,
              204,
              204,
              403,
              404,
              401,
              404),
          // ---- canales ----
          row(
              "GET",
              "/servers/{serverId}/channels",
              "—",
              SERVER,
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          row(
              "POST",
              "/servers/{serverId}/channels",
              "—",
              SERVER,
              w -> "{\"name\":\"nuevo\",\"kind\":\"text\"}",
              201,
              201,
              403,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}",
              "público",
              Map.of("channelId", Ref.GENERAL),
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}",
              "privado, member no está",
              Map.of("channelId", Ref.PRIVATE),
              null,
              200,
              200,
              404,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}",
              "privado, member sí está",
              Map.of("channelId", Ref.PRIVATE_IN),
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          row(
              "POST",
              "/channels/{channelId}/members",
              "privado, member no está",
              Map.of("channelId", Ref.PRIVATE),
              w -> "{\"userId\":" + w.refs.get(Ref.NEWCOMER) + "}",
              204,
              204,
              404,
              404,
              401,
              404),
          row(
              "POST",
              "/channels/{channelId}/members",
              "privado, member sí está",
              Map.of("channelId", Ref.PRIVATE_IN),
              w -> "{\"userId\":" + w.refs.get(Ref.NEWCOMER) + "}",
              204,
              204,
              403,
              404,
              401,
              404),
          row(
              "DELETE",
              "/channels/{channelId}/members/{userId}",
              "privado, member no está",
              Map.of("channelId", Ref.PRIVATE, "userId", Ref.OWNER_ID),
              null,
              204,
              204,
              404,
              404,
              401,
              404),
          row(
              "DELETE",
              "/channels/{channelId}/members/{userId}",
              "privado, member sí está",
              Map.of("channelId", Ref.PRIVATE_IN, "userId", Ref.OWNER_ID),
              null,
              204,
              204,
              403,
              404,
              401,
              404),
          // ---- mensajes (hito 3): escribe y lee quien ve el canal ----
          row(
              "POST",
              "/channels/{channelId}/messages",
              "público",
              Map.of("channelId", Ref.GENERAL),
              w -> MESSAGE_BODY_TEMPLATE.formatted(UUID.randomUUID()),
              201,
              201,
              201,
              404,
              401,
              404),
          row(
              "POST",
              "/channels/{channelId}/messages",
              "privado, member no está",
              Map.of("channelId", Ref.PRIVATE),
              w -> MESSAGE_BODY_TEMPLATE.formatted(UUID.randomUUID()),
              201,
              201,
              404,
              404,
              401,
              404),
          row(
              "POST",
              "/channels/{channelId}/messages",
              "privado, member sí está",
              Map.of("channelId", Ref.PRIVATE_IN),
              w -> MESSAGE_BODY_TEMPLATE.formatted(UUID.randomUUID()),
              201,
              201,
              201,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}/messages",
              "público",
              Map.of("channelId", Ref.GENERAL),
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}/messages",
              "privado, member no está",
              Map.of("channelId", Ref.PRIVATE),
              null,
              200,
              200,
              404,
              404,
              401,
              404),
          row(
              "GET",
              "/channels/{channelId}/messages",
              "privado, member sí está",
              Map.of("channelId", Ref.PRIVATE_IN),
              null,
              200,
              200,
              200,
              404,
              401,
              404),
          // ---- invitaciones ----
          row(
              "POST",
              "/servers/{serverId}/invites",
              "—",
              SERVER,
              w -> JSON_OBJ,
              201,
              201,
              403,
              404,
              401,
              404),
          row(
              "GET",
              "/invites/{code}",
              "vigente",
              Map.of("code", Ref.CODE),
              null,
              200,
              200,
              200,
              200,
              401,
              200),
          // El expulsado puede volver con una invitación vigente: no hay bans (docs/v2.md).
          row(
              "POST",
              "/invites/{code}/accept",
              "vigente",
              Map.of("code", Ref.CODE),
              null,
              200,
              200,
              200,
              200,
              401,
              200));

  private static final AtomicInteger WORLDS = new AtomicInteger();

  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired InviteService inviteService;
  @Autowired JwtService jwt;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping handlerMapping;

  // ---- una celda = un test ----

  @TestFactory
  Stream<DynamicTest> matrix() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Row row : MATRIX) {
      for (Actor actor : Actor.values()) {
        int expected = row.expected().get(actor.ordinal());
        String name =
            "%s%s · %s → %d"
                .formatted(
                    row.key(),
                    row.note().equals("—") ? "" : " [" + row.note() + "]",
                    actor.label,
                    expected);
        tests.add(DynamicTest.dynamicTest(name, () -> check(row, actor, expected)));
      }
    }
    return tests.stream();
  }

  private void check(Row row, Actor actor, int expected) throws Exception {
    World w = newWorld();
    MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(row.method()), row.path(w));
    if (actor != Actor.ANON) {
      req.header("Authorization", "Bearer " + w.tokens.get(actor));
    }
    if (row.body() != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(row.body().apply(w));
    }
    mvc.perform(req).andExpect(status().is(expected));
  }

  // ---- si alguien agrega un endpoint y no lo pone en la matriz, esto falla ----

  @Test
  void everyEndpointIsInTheMatrix() {
    Set<String> inMatrix = new TreeSet<>();
    MATRIX.forEach(r -> inMatrix.add(r.key()));

    Set<String> real = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      Set<String> patterns = info.getPathPatternsCondition().getPatternValues();
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : patterns) {
        if (pattern.startsWith("/auth/") || pattern.startsWith("/error")) {
          continue; // públicos o internos de Spring: no son recursos con dueño
        }
        for (RequestMethod m : methods) {
          real.add(m + " " + pattern);
        }
        assertThat(methods).as("%s debe declarar su método HTTP", pattern).isNotEmpty();
      }
    }

    assertThat(inMatrix)
        .as("la matriz y los endpoints reales deben coincidir exactamente")
        .isEqualTo(real);
  }

  // ---- docs/AUTHZ.md es la misma tabla ----

  private static final String START = "<!-- matrix:start -->";
  private static final String END = "<!-- matrix:end -->";
  private static final Path DOC = Path.of("..", "docs", "AUTHZ.md");

  @Test
  void authzDocMatchesTheMatrix() throws IOException {
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
    sb.append("| Endpoint | Caso");
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

  // ---- escenario ----

  private User user(String email, String name) {
    return users.save(new User(email, "no-login-needed", name));
  }

  private void join(Long serverId, User u, Role role) {
    serverMembers.save(new ServerMember(serverId, u.getId(), role));
  }

  private World newWorld() {
    int n = WORLDS.incrementAndGet();
    String domain = "@matrix" + n + ".test";
    User owner = user("owner" + domain, "Owner");
    User admin = user("admin" + domain, "Admin");
    User member = user("member" + domain, "Member");
    User other = user("other" + domain, "Other");
    User kicked = user("kicked" + domain, "Kicked");
    User victim = user("victim" + domain, "Victim");
    User newcomer = user("newcomer" + domain, "Newcomer");

    ServerResponse server = serverService.create(owner.getId(), new CreateServerRequest("S" + n));
    Long s = server.id();
    join(s, admin, Role.ADMIN);
    join(s, member, Role.MEMBER);
    join(s, kicked, Role.MEMBER);
    join(s, victim, Role.MEMBER);
    join(s, newcomer, Role.MEMBER);
    serverService.create(other.getId(), new CreateServerRequest("Ajeno" + n));

    ChannelResponse general = channelService.list(owner.getId(), s).get(0);
    ChannelResponse priv =
        channelService.create(owner.getId(), s, new CreateChannelRequest("secreto", "private"));
    ChannelResponse privIn =
        channelService.create(owner.getId(), s, new CreateChannelRequest("equipo", "private"));
    channelService.addMember(owner.getId(), privIn.id(), member.getId());
    channelService.addMember(owner.getId(), privIn.id(), kicked.getId());

    InviteResponse invite =
        inviteService.create(owner.getId(), s, new CreateInviteRequest(null, null));

    // Con el servicio real, justo antes del request: el token sigue vigente, la membresía no.
    serverService.kick(owner.getId(), s, kicked.getId());

    World w = new World();
    w.tokens.put(Actor.OWNER, jwt.issue(owner));
    w.tokens.put(Actor.ADMIN, jwt.issue(admin));
    w.tokens.put(Actor.MEMBER, jwt.issue(member));
    w.tokens.put(Actor.OTHER, jwt.issue(other));
    w.tokens.put(Actor.KICKED, jwt.issue(kicked));
    w.refs.put(Ref.SERVER, String.valueOf(s));
    w.refs.put(Ref.GENERAL, String.valueOf(general.id()));
    w.refs.put(Ref.PRIVATE, String.valueOf(priv.id()));
    w.refs.put(Ref.PRIVATE_IN, String.valueOf(privIn.id()));
    w.refs.put(Ref.VICTIM, String.valueOf(victim.getId()));
    w.refs.put(Ref.OWNER_ID, String.valueOf(owner.getId()));
    w.refs.put(Ref.NEWCOMER, String.valueOf(newcomer.getId()));
    w.refs.put(Ref.CODE, invite.code());
    return w;
  }
}
