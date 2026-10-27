package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.ChatApplication;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.user.User;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Dos instancias del backend en la misma JVM, con la misma base y el mismo RabbitMQ (ADR 0002): lo
 * que se guarda en una llega a los clientes conectados a la otra, porque el relay publica en
 * RabbitMQ y no en memoria.
 */
class TwoInstancesIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  private static ConfigurableApplicationContext instanceB;
  private static int portB;

  @Autowired PostgreSQLContainer postgres;
  @Autowired RabbitMQContainer rabbit;
  @Autowired Environment env;

  /** La instancia B se levanta una sola vez, la primera vez que un test la pide. */
  private synchronized MessageService startInstanceB() {
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

  private StompTestClient connectToB(User user) {
    return StompTestClient.connect(portB, token(user));
  }

  private static void sendOn(MessageService instance, Long userId, Long channelId, String body) {
    instance.send(userId, channelId, new SendMessageRequest(UUID.randomUUID(), body));
  }

  @Test
  void aMessageSavedOnInstanceA_reachesAClientOnInstanceB() {
    MessageService onB = startInstanceB();
    assertThat(onB).isNotSameAs(messages);
    try (StompTestClient clientOnB = connectToB(member)) {
      assertThat(clientOnB.subscribe(ChannelTopic.of(general))).isNull();

      sendOn(messages, admin.getId(), general, "desde A");

      String json = clientOnB.nextMessage(WAIT);
      assertThat(json).isNotNull();
      assertThat((String) JsonPath.read(json, "$.body")).isEqualTo("desde A");
      assertThat((Integer) JsonPath.read(json, "$.seq")).isEqualTo(1);
    }
  }

  @Test
  void aMessageSavedOnInstanceB_reachesAClientOnInstanceA() {
    MessageService onB = startInstanceB();
    try (StompTestClient clientOnA = connect(member)) {
      assertThat(clientOnA.subscribe(ChannelTopic.of(general))).isNull();

      sendOn(onB, admin.getId(), general, "desde B");

      String json = clientOnA.nextMessage(WAIT);
      assertThat(json).isNotNull();
      assertThat((String) JsonPath.read(json, "$.body")).isEqualTo("desde B");
    }
  }

  @Test
  void alternatingBetweenInstances_bothClientsSeeTheSameSequence() {
    MessageService onB = startInstanceB();
    try (StompTestClient clientOnA = connect(member);
        StompTestClient clientOnB = connectToB(owner)) {
      assertThat(clientOnA.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(clientOnB.subscribe(ChannelTopic.of(general))).isNull();

      for (int i = 1; i <= 10; i++) {
        sendOn(i % 2 == 0 ? messages : onB, admin.getId(), general, "m" + i);
      }

      List<Integer> seenOnA = new ArrayList<>();
      List<Integer> seenOnB = new ArrayList<>();
      for (int i = 1; i <= 10; i++) {
        seenOnA.add(JsonPath.read(clientOnA.nextMessage(WAIT), "$.seq"));
        seenOnB.add(JsonPath.read(clientOnB.nextMessage(WAIT), "$.seq"));
      }
      List<Integer> expected = IntStream.rangeClosed(1, 10).boxed().toList();
      assertThat(seenOnA).containsExactlyElementsOf(expected);
      assertThat(seenOnB).containsExactlyElementsOf(expected);
    }
  }
}
