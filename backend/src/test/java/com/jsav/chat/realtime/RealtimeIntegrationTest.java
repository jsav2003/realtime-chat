package com.jsav.chat.realtime;

import com.jsav.chat.ChatApplication;
import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.auth.JwtService;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Base de los tests de tiempo real: servidor HTTP en un puerto aleatorio, Postgres y RabbitMQ
 * reales, y un servidor con owner, admin, member y un usuario ajeno. Todos comparten un contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
abstract class RealtimeIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @LocalServerPort int port;

  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired MessageService messages;
  @Autowired JwtService jwt;
  @Autowired PostgreSQLContainer postgres;
  @Autowired RabbitMQContainer rabbit;
  @Autowired Environment env;

  User owner;
  User admin;
  User member;
  User outsider;
  Long serverId;
  Long general;
  Long secret;

  @BeforeEach
  void setUpWorld() {
    int run = RUNS.incrementAndGet();
    owner = users.save(new User("rt" + run + "-owner@x.test", "x", "Owner"));
    admin = users.save(new User("rt" + run + "-admin@x.test", "x", "Admin"));
    member = users.save(new User("rt" + run + "-member@x.test", "x", "Member"));
    outsider = users.save(new User("rt" + run + "-out@x.test", "x", "Outsider"));
    serverId = serverService.create(owner.getId(), new CreateServerRequest("Rt" + run)).id();
    serverMembers.save(new ServerMember(serverId, admin.getId(), Role.ADMIN));
    serverMembers.save(new ServerMember(serverId, member.getId(), Role.MEMBER));
    general = channelService.list(owner.getId(), serverId).get(0).id();
    secret =
        channelService
            .create(owner.getId(), serverId, new CreateChannelRequest("secreto", "private"))
            .id();
  }

  private static ConfigurableApplicationContext instanceB;
  static int portB;

  /** Un bean de la instancia B; antes hay que haberla levantado con {@link #startInstanceB()}. */
  <T> T instanceBBean(Class<T> type) {
    return instanceB.getBean(type);
  }

  /** La instancia B se levanta una sola vez, la primera vez que un test la pide. */
  synchronized MessageService startInstanceB() {
    if (instanceB == null) {
      instanceB =
          new SpringApplicationBuilder(ChatApplication.class)
              .run(
                  "--server.port=0",
                  "--spring.jmx.enabled=false",
                  "--spring.datasource.url=" + postgres.getJdbcUrl(),
                  "--spring.datasource.username=" + postgres.getUsername(),
                  "--spring.datasource.password=" + postgres.getPassword(),
                  "--spring.rabbitmq.host=" + rabbit.getHost(),
                  "--spring.rabbitmq.port=" + rabbit.getAmqpPort(),
                  "--spring.rabbitmq.username=" + rabbit.getAdminUsername(),
                  "--spring.rabbitmq.password=" + rabbit.getAdminPassword(),
                  "--app.realtime.relay-host=" + env.getProperty("app.realtime.relay-host"),
                  "--app.realtime.relay-port=" + env.getProperty("app.realtime.relay-port"),
                  "--app.realtime.relay-login=" + env.getProperty("app.realtime.relay-login"),
                  "--app.realtime.relay-passcode=" + env.getProperty("app.realtime.relay-passcode"),
                  "--app.jwt.secret=" + env.getProperty("app.jwt.secret"));

      portB = Integer.parseInt(instanceB.getEnvironment().getProperty("local.server.port"));
    }
    return instanceB.getBean(MessageService.class);
  }

  @AfterAll
  static void stopInstanceB() {
    if (instanceB != null) {
      instanceB.close();
      instanceB = null;
    }
  }

  StompTestClient connectToB(User user) {
    return StompTestClient.connect(portB, token(user));
  }

  MessageService.Sent send(User user, Long channelId, String body) {
    return messages.send(user.getId(), channelId, new SendMessageRequest(UUID.randomUUID(), body));
  }

  String token(User user) {
    return jwt.issue(user);
  }

  StompTestClient connect(User user) {
    return StompTestClient.connect(port, token(user));
  }
}
