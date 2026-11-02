package com.jsav.chat.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** El resumen que sale en la lista: una sola línea y corto, nunca el mensaje entero. */
class NotificationServiceSnippetTest {

  @Test
  void aShortMessage_isKeptAsIs() {
    assertThat(NotificationService.snippet("hola @beto")).isEqualTo("hola @beto");
  }

  @Test
  void lineBreaksAndRepeatedSpacesBecomeOneSpace() {
    assertThat(NotificationService.snippet("  hola\n\n   @beto \t que tal  "))
        .isEqualTo("hola @beto que tal");
  }

  @Test
  void aLongMessage_isCutAtTheLimitWithAnEllipsis() {
    String snippet = NotificationService.snippet("a".repeat(500));

    assertThat(snippet).hasSize(NotificationService.SNIPPET_LENGTH).endsWith("…");
  }

  @Test
  void aMessageJustAtTheLimit_isNotCut() {
    String exactly = "b".repeat(NotificationService.SNIPPET_LENGTH);

    assertThat(NotificationService.snippet(exactly)).isEqualTo(exactly);
  }
}
