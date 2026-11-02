package com.jsav.chat.mention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Los vectores de aquí son la especificación de qué cuenta como mención. El frontend tiene la misma
 * regla en {@code lib/mentions.ts}: si se cambia un vector, hay que revisar los dos.
 */
class MentionParserTest {

  static Stream<Arguments> vectors() {
    return Stream.of(
        Arguments.of("hola @beto", List.of("beto")),
        Arguments.of("@beto hola", List.of("beto")),
        Arguments.of("gracias @beto.", List.of("beto")),
        Arguments.of("(@ana) ¡@beto!", List.of("ana", "beto")),
        Arguments.of("@ana,@beto", List.of("ana", "beto")),
        Arguments.of("hola\n@ana", List.of("ana")),
        Arguments.of("@ana's", List.of("ana")),
        Arguments.of("@ANA y @Ana", List.of("ana")),
        Arguments.of("@zeta @alfa @zeta", List.of("zeta", "alfa")),
        Arguments.of("@ana_2 @_x_ @__a", List.of("ana_2", "_x_", "__a")),
        // Un correo no menciona a nadie: el @ va pegado a una letra.
        Arguments.of("escríbeme a ana@correo.com", List.of()),
        Arguments.of("x.y@ana.com", List.of()),
        Arguments.of("ñ@beto", List.of()),
        Arguments.of("@@beto", List.of()),
        // Largo del handle: de 3 a 20.
        Arguments.of("@ab", List.of()),
        Arguments.of("@abc", List.of("abc")),
        Arguments.of("@" + "a".repeat(20), List.of("a".repeat(20))),
        Arguments.of("@" + "a".repeat(21), List.of()),
        // Un handle no termina a la mitad de una palabra.
        Arguments.of("@betoñ", List.of()),
        Arguments.of("@beto1ñandú", List.of()),
        Arguments.of("@ y @ ", List.of()),
        Arguments.of("", List.of()));
  }

  @ParameterizedTest(name = "[{index}] \"{0}\" → {1}")
  @MethodSource("vectors")
  void readsOnlyRealMentions(String body, List<String> expected) {
    assertThat(MentionParser.usernamesIn(body)).containsExactlyElementsOf(expected);
  }

  @Test
  void readsAtMostTenDistinctHandles_inOrderOfAppearance() {
    String body =
        String.join(
            " ", IntStream.rangeClosed(1, 12).mapToObj(i -> "@user%02d".formatted(i)).toList());

    assertThat(MentionParser.usernamesIn(body))
        .hasSize(MentionParser.MAX_MENTIONS)
        .containsExactly(
            "user01", "user02", "user03", "user04", "user05", "user06", "user07", "user08",
            "user09", "user10");
  }

  @Test
  void repeatedHandlesDoNotCountTowardsTheLimit() {
    String body = "@ana ".repeat(50) + "@beto";

    assertThat(MentionParser.usernamesIn(body)).containsExactly("ana", "beto");
  }
}
