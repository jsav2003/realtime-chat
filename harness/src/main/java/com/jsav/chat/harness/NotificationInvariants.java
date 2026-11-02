package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.NotificationView;
import com.jsav.chat.harness.Invariants.Counter;
import com.jsav.chat.harness.Invariants.Verdict;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Las invariantes de las notificaciones (DESIGN.md §8.3), comprobadas al terminar la corrida contra
 * lo que cada persona recibe por {@code GET /notifications}. Todas deben dar cero violaciones.
 *
 * <ol>
 *   <li><b>Completa:</b> cada mención de un mensaje que quedó guardado terminó notificada.
 *   <li><b>Sin duplicados:</b> ninguna persona tiene dos notificaciones por el mismo mensaje.
 *   <li><b>Fiel:</b> nadie recibió una notificación de un mensaje que no lo mencionaba (ni de uno
 *       que escribió él mismo).
 * </ol>
 *
 * <p>Una mención se identifica por (seq del mensaje, índice del cliente mencionado): la corrida usa
 * un solo canal, así que el seq basta.
 */
public final class NotificationInvariants {

  /** Una mención esperada o recibida: el mensaje (por seq) y a quién (índice de cliente). */
  public record Mention(long seq, int user) {}

  /** Lo que el servidor le devolvió a una persona. */
  public record Received(int user, List<NotificationView> items) {}

  private NotificationInvariants() {}

  public static List<Verdict> check(Set<Mention> expected, List<Received> received) {
    return List.of(
        complete(expected, received), noDuplicates(received), faithful(expected, received));
  }

  public static boolean allHold(List<Verdict> verdicts) {
    return verdicts.stream().allMatch(Verdict::holds);
  }

  static Verdict complete(Set<Mention> expected, List<Received> received) {
    Counter c = new Counter();
    Set<Mention> got = new HashSet<>();
    for (Received r : received) {
      r.items().forEach(n -> got.add(new Mention(n.seq(), r.user())));
    }
    for (Mention m : expected) {
      if (!got.contains(m)) {
        c.add(
            "al cliente %d no le llegó la mención del mensaje con seq %d"
                .formatted(m.user(), m.seq()));
      }
    }
    return c.verdict("Completa");
  }

  static Verdict noDuplicates(List<Received> received) {
    Counter c = new Counter();
    for (Received r : received) {
      Set<Long> seqs = new HashSet<>();
      Set<Long> messageIds = new HashSet<>();
      for (NotificationView n : r.items()) {
        if (!seqs.add(n.seq()) || !messageIds.add(n.messageId())) {
          c.add(
              "el cliente %d tiene más de una notificación por el mensaje con seq %d"
                  .formatted(r.user(), n.seq()));
        }
      }
    }
    return c.verdict("Sin duplicados");
  }

  static Verdict faithful(Set<Mention> expected, List<Received> received) {
    Counter c = new Counter();
    for (Received r : received) {
      for (NotificationView n : r.items()) {
        if (!expected.contains(new Mention(n.seq(), r.user()))) {
          c.add(
              "el cliente %d recibió una notificación del mensaje con seq %d, que no lo mencionaba"
                  .formatted(r.user(), n.seq()));
        }
      }
    }
    return c.verdict("Fiel");
  }
}
