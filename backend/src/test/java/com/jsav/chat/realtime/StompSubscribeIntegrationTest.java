package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SUBSCRIBE por un WebSocket real: quién puede suscribirse a qué (DESIGN.md §4). */
class StompSubscribeIntegrationTest extends RealtimeIntegrationTest {

  @Test
  void connectWithoutAToken_getsA401ErrorFrame() {
    assertThat(StompTestClient.connectError(port, null)).startsWith("401");
  }

  @Test
  void connectWithAForgedToken_getsA401ErrorFrame() {
    assertThat(StompTestClient.connectError(port, "no.es.un.jwt")).startsWith("401");
  }

  @Test
  void aMemberSubscribesToAPublicChannel() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).isNull();
    }
  }

  @Test
  void anOutsiderGets404OnAPublicChannel() {
    try (StompTestClient c = connect(outsider)) {
      assertThat(c.subscribe(ChannelTopic.of(general))).startsWith("404");
    }
  }

  @Test
  void aMemberNotInAPrivateChannelGets404() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(secret))).startsWith("404");
    }
  }

  @Test
  void anAdminSubscribesToAPrivateChannel() {
    try (StompTestClient c = connect(admin)) {
      assertThat(c.subscribe(ChannelTopic.of(secret))).isNull();
    }
  }

  @Test
  void aChannelThatDoesNotExistGets404() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe(ChannelTopic.of(999_999_999L))).startsWith("404");
    }
  }

  @Test
  void aDestinationOutsideChannelTopicsGets404() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe("/topic/other")).startsWith("404");
    }
  }

  @Test
  void aWildcardDestinationGets404() {
    try (StompTestClient c = connect(member)) {
      assertThat(c.subscribe("/topic/channel.*")).startsWith("404");
    }
  }
}
