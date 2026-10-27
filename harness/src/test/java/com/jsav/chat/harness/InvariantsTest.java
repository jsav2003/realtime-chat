package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.ClientLog.Source;
import com.jsav.chat.harness.Invariants.Verdict;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Las invariantes tienen que morder: cada una se prueba contra un registro corrompido a propósito,
 * porque un verificador que nunca falla no demuestra nada. El caso limpio da cero en las cuatro.
 */
class InvariantsTest {

  private static final int N = 6;

  private final List<UUID> ids = new ArrayList<>();
  private List<Stored> truth;
  private List<ClientLog> clients;
  private Ledger ledger;
  private Set<UUID> planned;

  /** Un mundo sano: 6 mensajes, 3 clientes que los tienen todos, y todos confirmados. */
  InvariantsTest() {
    truth = new ArrayList<>();
    for (int seq = 1; seq <= N; seq++) {
      UUID id = UUID.nameUUIDFromBytes(("m" + seq).getBytes());
      ids.add(id);
      truth.add(new Stored(seq, id, "m" + seq));
    }
    clients = new ArrayList<>();
    for (int c = 0; c < 3; c++) {
      ClientLog log = new ClientLog();
      truth.forEach(m -> log.receive(m, Source.LIVE));
      clients.add(log);
    }
    ledger = new Ledger();
    truth.forEach(m -> ledger.confirmed(m.clientMsgId(), m.seq(), 201));
    planned = new HashSet<>(ids);
  }

  private List<Verdict> check() {
    return Invariants.check(truth, clients, ledger, planned);
  }

  private static Verdict named(List<Verdict> verdicts, String name) {
    return verdicts.stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void aCleanRun_hasZeroViolationsInAllFour() {
    List<Verdict> verdicts = check();

    assertThat(verdicts).hasSize(4);
    assertThat(verdicts).allMatch(Verdict::holds);
    assertThat(Invariants.allHold(verdicts)).isTrue();
  }

  // ---- 1. completo ----

  @Test
  void complete_catchesAClientMissingAMessage() {
    ClientLog gappy = new ClientLog();
    truth.stream().filter(m -> m.seq() != 3).forEach(m -> gappy.receive(m, Source.LIVE));
    clients.set(1, gappy);

    Verdict v = named(check(), "Completo");

    assertThat(v.violations()).isEqualTo(1);
    assertThat(v.examples().get(0)).contains("cliente 1").contains("seq 3");
  }

  @Test
  void complete_catchesAHoleInTheServerSequence() {
    truth.removeIf(m -> m.seq() == 4);

    assertThat(named(check(), "Completo").violations()).isGreaterThanOrEqualTo(1);
  }

  // ---- 2. sin duplicados ----

  @Test
  void noDuplicates_catchesTheSameMessageUnderTwoSeqs() {
    // Una falla de idempotencia: el reintento creó un segundo mensaje con otro seq.
    truth.add(new Stored(N + 1, ids.get(0), "m1 otra vez"));

    Verdict v = named(check(), "Sin duplicados");

    assertThat(v.violations()).isGreaterThanOrEqualTo(1);
    assertThat(v.examples().get(0)).contains("dos seq");
  }

  @Test
  void noDuplicates_catchesTwoDifferentMessagesWithTheSameSeq() {
    clients.get(2).receive(new Stored(2, UUID.randomUUID(), "impostor"), Source.RESYNC);

    Verdict v = named(check(), "Sin duplicados");

    assertThat(v.violations()).isEqualTo(1);
    assertThat(v.examples().get(0)).contains("cliente 2");
  }

  @Test
  void noDuplicates_ignoresHarmlessRepeatedDeliveries() {
    // Que llegue dos veces lo mismo (en vivo y por resync) es normal: el cliente lo descarta.
    clients.get(0).receive(truth.get(0), Source.RESYNC);

    assertThat(named(check(), "Sin duplicados").holds()).isTrue();
    assertThat(clients.get(0).repeatedDeliveries()).isEqualTo(1);
  }

  // ---- 3. mismo orden ----

  @Test
  void sameOrder_catchesTwoMessagesSwapped() {
    ClientLog swapped = new ClientLog();
    for (Stored m : truth) {
      Stored shown =
          m.seq() == 2
              ? new Stored(2, ids.get(2), "m3")
              : m.seq() == 3 ? new Stored(3, ids.get(1), "m2") : m;
      swapped.receive(shown, Source.LIVE);
    }
    clients.set(0, swapped);

    Verdict v = named(check(), "Mismo orden");

    assertThat(v.violations()).isEqualTo(2);
  }

  @Test
  void sameOrder_catchesAClientWithFewerMessages() {
    ClientLog short_ = new ClientLog();
    truth.subList(0, 4).forEach(m -> short_.receive(m, Source.LIVE));
    clients.set(1, short_);

    assertThat(named(check(), "Mismo orden").violations()).isEqualTo(2);
  }

  // ---- 4. fiel ----

  @Test
  void faithful_catchesAConfirmedSendThatIsNotInTheServer() {
    truth.removeIf(m -> m.seq() == N);

    Verdict v = named(check(), "Fiel");

    assertThat(v.violations()).isEqualTo(1);
    assertThat(v.examples().get(0)).contains("0 veces");
  }

  @Test
  void faithful_catchesAConfirmedSendUnderAnotherSeq() {
    Ledger other = new Ledger();
    truth.forEach(m -> other.confirmed(m.clientMsgId(), m.seq() == 2 ? 99 : m.seq(), 201));
    ledger = other;

    Verdict v = named(check(), "Fiel");

    assertThat(v.violations()).isEqualTo(1);
    assertThat(v.examples().get(0)).contains("se confirmó con seq 99");
  }

  @Test
  void faithful_catchesAnUnconfirmedSendThatAppearsTwice() {
    UUID lost = UUID.randomUUID();
    planned.add(lost);
    ledger.gaveUp(lost);
    truth.add(new Stored(N + 1, lost, "perdido"));
    truth.add(new Stored(N + 2, lost, "perdido"));

    assertThat(named(check(), "Fiel").violations()).isEqualTo(1);
  }

  @Test
  void faithful_allowsAnUnconfirmedSendThatWasSavedOnce() {
    UUID lost = UUID.randomUUID();
    planned.add(lost);
    ledger.gaveUp(lost);
    truth.add(new Stored(N + 1, lost, "se guardó, pero no llegó la respuesta"));

    assertThat(named(check(), "Fiel").holds()).isTrue();
  }

  @Test
  void faithful_catchesAMessageNobodySent() {
    truth.add(new Stored(N + 1, UUID.randomUUID(), "fantasma"));

    Verdict v = named(check(), "Fiel");

    assertThat(v.violations()).isEqualTo(1);
    assertThat(v.examples().get(0)).contains("nadie envió");
  }

  @Test
  void faithful_catchesAResendThatChangedTheSeq() {
    ledger.replayed(ids.get(0), 1, 7, 201);

    assertThat(named(check(), "Fiel").violations()).isEqualTo(1);
  }

  @Test
  void faithful_acceptsAResendThatReturnsTheSameSeq() {
    ledger.replayed(ids.get(0), 1, 1, 200);

    assertThat(named(check(), "Fiel").holds()).isTrue();
  }
}
