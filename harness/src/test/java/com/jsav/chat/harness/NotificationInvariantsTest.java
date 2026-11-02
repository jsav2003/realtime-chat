package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.ApiClient.NotificationView;
import com.jsav.chat.harness.Invariants.Verdict;
import com.jsav.chat.harness.NotificationInvariants.Mention;
import com.jsav.chat.harness.NotificationInvariants.Received;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Cada invariante de las notificaciones tiene que detectar lo que dice detectar. */
class NotificationInvariantsTest {

  private static NotificationView note(long id, long seq) {
    // El id del mensaje es el seq más mil: aquí solo importa que sea distinto por mensaje.
    return new NotificationView(id, 1000 + seq, 1, seq, false);
  }

  private static Verdict verdict(List<Verdict> all, String name) {
    return all.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
  }

  private static final Set<Mention> EXPECTED =
      Set.of(new Mention(1, 1), new Mention(2, 1), new Mention(2, 2));

  private static List<Received> perfect() {
    return List.of(
        new Received(0, List.of()),
        new Received(1, List.of(note(1, 1), note(2, 2))),
        new Received(2, List.of(note(3, 2))));
  }

  @Test
  void whenEverythingArrivesOnce_everyInvariantHolds() {
    List<Verdict> verdicts = NotificationInvariants.check(EXPECTED, perfect());

    assertThat(NotificationInvariants.allHold(verdicts)).isTrue();
    assertThat(verdicts)
        .extracting(Verdict::name)
        .containsExactly("Completa", "Sin duplicados", "Fiel");
  }

  @Test
  void aMissingMention_breaksComplete_only() {
    List<Received> received =
        List.of(
            new Received(0, List.of()),
            new Received(1, List.of(note(1, 1))), // falta la del seq 2
            new Received(2, List.of(note(3, 2))));

    List<Verdict> verdicts = NotificationInvariants.check(EXPECTED, received);

    assertThat(verdict(verdicts, "Completa").violations()).isEqualTo(1);
    assertThat(verdict(verdicts, "Completa").examples().get(0))
        .contains("cliente 1")
        .contains("seq 2");
    assertThat(verdict(verdicts, "Sin duplicados").holds()).isTrue();
    assertThat(verdict(verdicts, "Fiel").holds()).isTrue();
  }

  @Test
  void theSameMentionTwice_breaksNoDuplicates() {
    List<Received> received =
        List.of(
            new Received(0, List.of()),
            new Received(1, List.of(note(1, 1), note(2, 2), note(9, 2))),
            new Received(2, List.of(note(3, 2))));

    List<Verdict> verdicts = NotificationInvariants.check(EXPECTED, received);

    assertThat(verdict(verdicts, "Sin duplicados").violations()).isEqualTo(1);
    assertThat(verdict(verdicts, "Completa").holds()).isTrue();
  }

  @Test
  void aNotificationForAMessageThatDidNotMentionThem_breaksFaithful() {
    List<Received> received =
        List.of(
            new Received(0, List.of(note(8, 2))), // el cliente 0 no fue mencionado en el seq 2
            new Received(1, List.of(note(1, 1), note(2, 2))),
            new Received(2, List.of(note(3, 2))));

    List<Verdict> verdicts = NotificationInvariants.check(EXPECTED, received);

    assertThat(verdict(verdicts, "Fiel").violations()).isEqualTo(1);
    assertThat(verdict(verdicts, "Fiel").examples().get(0)).contains("cliente 0").contains("seq 2");
    assertThat(verdict(verdicts, "Completa").holds()).isTrue();
  }

  @Test
  void aNotificationForAMessageYouWroteYourself_breaksFaithful() {
    // El seq 5 lo escribió el cliente 2 y no menciona a nadie: no debería haber nada para él.
    List<Received> received =
        List.of(
            new Received(0, List.of()),
            new Received(1, List.of(note(1, 1), note(2, 2))),
            new Received(2, List.of(note(3, 2), note(4, 5))));

    List<Verdict> verdicts = NotificationInvariants.check(EXPECTED, received);

    assertThat(verdict(verdicts, "Fiel").violations()).isEqualTo(1);
  }

  @Test
  void examplesAreCapped() {
    List<Received> received = List.of(new Received(0, List.of()));
    Set<Mention> many =
        Set.of(
            new Mention(1, 0),
            new Mention(2, 0),
            new Mention(3, 0),
            new Mention(4, 0),
            new Mention(5, 0),
            new Mention(6, 0),
            new Mention(7, 0));

    Verdict complete = verdict(NotificationInvariants.check(many, received), "Completa");

    assertThat(complete.violations()).isEqualTo(7);
    assertThat(complete.examples()).hasSizeLessThanOrEqualTo(5);
  }
}
