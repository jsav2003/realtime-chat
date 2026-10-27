package com.jsav.chat.realtime;

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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

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
