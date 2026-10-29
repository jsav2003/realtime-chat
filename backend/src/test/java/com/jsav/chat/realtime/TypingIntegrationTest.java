package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.typing.TypingService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * «Escribiendo…» de punta a punta con WebSockets reales y Redis real (ADR 0010): llega a quien
 * escucha el tópico {@code .typing}, no a quien escucha los mensajes, y se frena por usuario y
 * canal.
 */
class TypingIntegrationTest extends RealtimeIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(10);
  private static final Duration QUIET = Duration.ofMillis(1200);

  @Autowired TypingService typing;

  @Test
  void aSubscriberOfTheTypingTopic_learnsWhoIsTyping() {
    try (StompTestClient watcher = connect(admin)) {
      assertThat(watcher.subscribe(ChannelTopic.typingOf(general))).isNull();

      typing.typing(member.getId(), general);

      String json = watcher.nextMessage(WAIT);
      assertThat(json).isNotNull();
      assertThat(((Number) JsonPath.read(json, "$.userId")).longValue()).isEqualTo(member.getId());
      assertThat((String) JsonPath.read(json, "$.displayName")).isEqualTo("Member");
    }
  }

  @Test
  void aSubscriberOfTheMessagesTopic_doesNotReceiveTyping() {
    try (StompTestClient messagesOnly = connect(admin)) {
      assertThat(messagesOnly.subscribe(ChannelTopic.of(general))).isNull();

      typing.typing(member.getId(), general);

      assertThat(messagesOnly.nextMessage(QUIET)).isNull();
    }
  }

  @Test
  void aSubscriberOfTheTypingTopic_doesNotReceiveMessages() {
    try (StompTestClient typingOnly = connect(admin)) {
      assertThat(typingOnly.subscribe(ChannelTopic.typingOf(general))).isNull();

      send(member, general, "hola");

      assertThat(typingOnly.nextMessage(QUIET)).isNull();
    }
  }

  @Test
  void twoNoticesInARow_publishOnlyOne() {
    try (StompTestClient watcher = connect(admin)) {
      assertThat(watcher.subscribe(ChannelTopic.typingOf(general))).isNull();

      typing.typing(member.getId(), general);
      typing.typing(member.getId(), general);
      typing.typing(member.getId(), general);

      assertThat(watcher.nextMessage(WAIT)).isNotNull();
      assertThat(watcher.nextMessage(QUIET)).isNull();
    }
  }

  @Test
  void afterTheWindow_theSameUserCanNoticeAgain() throws Exception {
    try (StompTestClient watcher = connect(admin)) {
      assertThat(watcher.subscribe(ChannelTopic.typingOf(general))).isNull();

      typing.typing(member.getId(), general);
      assertThat(watcher.nextMessage(WAIT)).isNotNull();

      Thread.sleep(2300);
      typing.typing(member.getId(), general);

      assertThat(watcher.nextMessage(WAIT)).isNotNull();
    }
  }

  @Test
  void theBrakeIsPerUser_soTwoPeopleTypingBothShow() {
    try (StompTestClient watcher = connect(owner)) {
      assertThat(watcher.subscribe(ChannelTopic.typingOf(general))).isNull();

      typing.typing(member.getId(), general);
      typing.typing(admin.getId(), general);

      assertThat(watcher.nextMessage(WAIT)).isNotNull();
      assertThat(watcher.nextMessage(WAIT)).isNotNull();
    }
  }

  @Test
  void aStranger_getsA404_andNothingIsPublished() {
    try (StompTestClient watcher = connect(admin)) {
      assertThat(watcher.subscribe(ChannelTopic.typingOf(general))).isNull();

      assertThatThrownBy(() -> typing.typing(outsider.getId(), general))
          .isInstanceOf(NotFoundException.class);

      assertThat(watcher.nextMessage(QUIET)).isNull();
    }
  }

  @Test
  void aMemberWhoCannotSeeAPrivateChannel_getsA404() {
    assertThatThrownBy(() -> typing.typing(member.getId(), secret))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void typingOnInstanceA_reachesAWatcherOnInstanceB() {
    startInstanceB();
    try (StompTestClient watcherOnB = connectToB(admin)) {
      assertThat(watcherOnB.subscribe(ChannelTopic.typingOf(general))).isNull();

      typing.typing(member.getId(), general);

      assertThat(watcherOnB.nextMessage(WAIT)).isNotNull();
    }
  }
}
