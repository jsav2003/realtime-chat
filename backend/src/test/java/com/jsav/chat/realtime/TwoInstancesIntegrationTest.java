package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Dos instancias del backend en la misma JVM, con la misma base y el mismo RabbitMQ (ADR 0002): lo
 * que se guarda en una llega a los clientes conectados a la otra, porque el relay publica en
 * RabbitMQ y no en memoria.
 */
class TwoInstancesIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

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
