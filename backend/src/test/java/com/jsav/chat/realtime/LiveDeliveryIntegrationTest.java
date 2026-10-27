package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Un mensaje guardado llega en vivo a quien está suscrito al canal, con su seq. */
class LiveDeliveryIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(10);

  @Test
  void aSubscribedClientReceivesTheMessageWithItsSeq() {
    try (StompTestClient listener = connect(member)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      send(admin, general, "hola en vivo");

      String json = listener.nextMessage(WAIT);
      assertThat(json).isNotNull();
      assertThat((Integer) JsonPath.read(json, "$.seq")).isEqualTo(1);
      assertThat((String) JsonPath.read(json, "$.body")).isEqualTo("hola en vivo");
      assertThat((String) JsonPath.read(json, "$.authorName")).isEqualTo("Admin");
      assertThat(((Number) JsonPath.read(json, "$.channelId")).longValue()).isEqualTo(general);
      assertThat((String) JsonPath.read(json, "$.createdAt")).contains("T");
    }
  }

  @Test
  void everySubscriberOfTheChannelReceivesIt_andOnlyThem() {
    try (StompTestClient a = connect(member);
        StompTestClient b = connect(owner);
        StompTestClient elsewhere = connect(admin)) {
      assertThat(a.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(b.subscribe(ChannelTopic.of(general))).isNull();
      assertThat(elsewhere.subscribe(ChannelTopic.of(secret))).isNull();

      send(member, general, "para el general");

      assertThat((Integer) JsonPath.read(a.nextMessage(WAIT), "$.seq")).isEqualTo(1);
      assertThat((Integer) JsonPath.read(b.nextMessage(WAIT), "$.seq")).isEqualTo(1);
      assertThat(elsewhere.nextMessage(Duration.ofSeconds(2))).isNull();
    }
  }

  @Test
  void messagesArriveInSeqOrder() {
    try (StompTestClient listener = connect(member)) {
      assertThat(listener.subscribe(ChannelTopic.of(general))).isNull();

      for (int i = 1; i <= 20; i++) {
        send(admin, general, "mensaje " + i);
      }

      List<Integer> seqs = new ArrayList<>();
      for (int i = 1; i <= 20; i++) {
        seqs.add(JsonPath.read(listener.nextMessage(WAIT), "$.seq"));
      }
      assertThat(seqs)
          .containsExactlyElementsOf(
              java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
    }
  }
}
