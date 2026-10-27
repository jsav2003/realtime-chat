package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelTopicTest {

  @Test
  void parsesAChannelDestination() {
    assertThat(ChannelTopic.parse("/topic/channel.123")).isEqualTo(Optional.of(123L));
  }

  @Test
  void ofAndParseAreInverse() {
    assertThat(ChannelTopic.parse(ChannelTopic.of(42))).isEqualTo(Optional.of(42L));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "/topic/channel.",
        "/topic/channel.0",
        "/topic/channel.007",
        "/topic/channel.-1",
        "/topic/channel.1.2",
        "/topic/channel.*",
        "/topic/channel.#",
        "/topic/channel.5/extra",
        "/topic/channel.99999999999999999999",
        "/topic/other.5",
        "/topic/#",
        "/queue/channel.5",
        "topic/channel.5",
        "/topic/channel.5 "
      })
  void rejectsAnythingElse(String destination) {
    assertThat(ChannelTopic.parse(destination)).isEmpty();
  }
}
