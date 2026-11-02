package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.harness.MentionsHarness.Options;
import org.junit.jupiter.api.Test;

class MentionsOptionsTest {

  @Test
  void withoutArguments_usesTheDefaults_andFortyMessagesPerClient() {
    Options o = Options.parse(new String[] {});

    assertThat(o.kills()).isEqualTo(6);
    assertThat(o.mentionRate()).isEqualTo(0.7);
    assertThat(o.scenario().messagesPerClient()).isEqualTo(40);
    assertThat(o.scenario().seed()).isEqualTo(Scenario.DEFAULTS.seed());
  }

  @Test
  void readsItsOwnOptions_andPassesTheRestToTheScenario() {
    Options o =
        Options.parse(
            new String[] {
              "--seed",
              "7",
              "--kills",
              "3",
              "--mention-rate",
              "0.5",
              "--clients",
              "5",
              "--messages",
              "10"
            });

    assertThat(o.kills()).isEqualTo(3);
    assertThat(o.mentionRate()).isEqualTo(0.5);
    assertThat(o.scenario().seed()).isEqualTo(7);
    assertThat(o.scenario().clients()).isEqualTo(5);
    assertThat(o.scenario().messagesPerClient()).isEqualTo(10);
  }

  @Test
  void rejectsValuesOutOfRange() {
    assertThatThrownBy(() -> Options.parse(new String[] {"--kills", "-1"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Options.parse(new String[] {"--mention-rate", "1.5"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnknownOptionIsStillRejectedByTheScenario() {
    assertThatThrownBy(() -> Options.parse(new String[] {"--nope", "1"}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
