package com.jsav.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RealtimePropertiesTest {

  @EnableConfigurationProperties(RealtimeProperties.class)
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsRelayAndOrigins() {
    runner
        .withPropertyValues(
            "app.realtime.relay-host=rabbit",
            "app.realtime.relay-port=61613",
            "app.realtime.relay-login=chat",
            "app.realtime.relay-passcode=secret",
            "app.realtime.allowed-origins=http://a.test,http://b.test")
        .run(
            ctx -> {
              RealtimeProperties p = ctx.getBean(RealtimeProperties.class);
              assertThat(p.relayHost()).isEqualTo("rabbit");
              assertThat(p.relayPort()).isEqualTo(61613);
              assertThat(p.relayLogin()).isEqualTo("chat");
              assertThat(p.relayPasscode()).isEqualTo("secret");
              assertThat(p.allowedOrigins()).containsExactly("http://a.test", "http://b.test");
            });
  }
}
