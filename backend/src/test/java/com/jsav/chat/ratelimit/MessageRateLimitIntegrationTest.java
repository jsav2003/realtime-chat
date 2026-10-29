package com.jsav.chat.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.TooManyRequestsException;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.message.MessageService.Sent;
import com.jsav.chat.server.Role;
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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * El límite de mensajes dentro del envío real, con Postgres y Redis reales. Capacidad 20 y recarga
 * de 1 por minuto: en lo que dura una prueba no vuelve ni un token (ADR 0009).
 */
@SpringBootTest(properties = {"app.ratelimit.capacity=20", "app.ratelimit.refill-per-minute=1"})
@Import(TestcontainersConfiguration.class)
class MessageRateLimitIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired MessageService service;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired JdbcClient jdbc;

  User owner;
  User member;
  Long general;
  Long secret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("rl" + run + "-owner@x.test", "x", "Owner"));
    member = users.save(new User("rl" + run + "-member@x.test", "x", "Member"));
    Long serverId =
        serverService.create(owner.getId(), new CreateServerRequest("RateLimit" + run)).id();
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
  }

  private static SendMessageRequest req(UUID key) {
    return new SendMessageRequest(key, "hola");
  }

  private long lastSeq(Long channelId) {
    return jdbc.sql("select last_seq from channels where id = :id")
        .param("id", channelId)
        .query(Long.class)
        .single();
  }

  private UUID sendTwenty(User user) {
    UUID last = null;
    for (int i = 0; i < 20; i++) {
      last = UUID.randomUUID();
      service.send(user.getId(), general, req(last));
    }
    return last;
  }

  @Test
  void el_mensaje_21_da_429_con_cuanto_esperar_y_no_gasta_un_seq() {
    sendTwenty(member);

    assertThatThrownBy(() -> service.send(member.getId(), general, req(UUID.randomUUID())))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 60L));

    // Un 429 llega antes de reservar el seq: la secuencia sigue sin huecos.
    assertThat(lastSeq(general)).isEqualTo(20);
  }

  @Test
  void reintentar_un_mensaje_ya_guardado_con_el_bucket_vacio_da_200_y_no_gasta_cupo() {
    UUID last = sendTwenty(member);

    Sent retry = service.send(member.getId(), general, req(last));

    assertThat(retry.replayed()).isTrue();
    assertThat(lastSeq(general)).isEqualTo(20);
  }

  @Test
  void con_el_bucket_vacio_un_canal_ajeno_sigue_dando_404_y_no_429() {
    sendTwenty(member);

    // El member no ve #secreto: AccessPolicy responde antes de mirar el límite.
    assertThatThrownBy(() -> service.send(member.getId(), secret, req(UUID.randomUUID())))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void el_limite_es_por_usuario() {
    sendTwenty(member);

    Sent other = service.send(owner.getId(), general, req(UUID.randomUUID()));

    assertThat(other.replayed()).isFalse();
    assertThat(other.message().seq()).isEqualTo(21);
  }
}
