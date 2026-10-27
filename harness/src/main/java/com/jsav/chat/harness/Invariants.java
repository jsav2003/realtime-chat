package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.Ledger.Confirmed;
import com.jsav.chat.harness.Ledger.Replay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Las cuatro invariantes de DESIGN.md §8.1, comprobadas al terminar la corrida contra la verdad del
 * servidor (el historial completo del canal leído al final) y lo que cada cliente tiene. Todas
 * deben dar cero violaciones.
 *
 * <ol>
 *   <li><b>Completo:</b> cada cliente tiene todos los seq de 1 a {@code last_seq}, sin huecos.
 *   <li><b>Sin duplicados:</b> ningún seq corresponde a dos mensajes distintos ni ningún mensaje a
 *       dos seq, ni en el servidor ni en ningún cliente.
 *   <li><b>Mismo orden:</b> todos los clientes tienen exactamente la misma secuencia que el
 *       servidor.
 *   <li><b>Fiel:</b> todo envío confirmado aparece exactamente una vez y con su seq; ningún envío
 *       aparece duplicado; no aparece nada que nadie envió; y reenviar uno ya confirmado devuelve
 *       el mismo seq.
 * </ol>
 */
public final class Invariants {

  /** El resultado de una invariante: cuántas violaciones y los primeros ejemplos. */
  public record Verdict(String name, long violations, List<String> examples) {
    public boolean holds() {
      return violations == 0;
    }
  }

  private static final int MAX_EXAMPLES = 5;

  private Invariants() {}

  public static List<Verdict> check(
      List<Stored> truth, List<ClientLog> clients, Ledger ledger, Set<UUID> planned) {
    return List.of(
        complete(truth, clients),
        noDuplicates(truth, clients),
        sameOrder(truth, clients),
        faithful(truth, ledger, planned));
  }

  public static boolean allHold(List<Verdict> verdicts) {
    return verdicts.stream().allMatch(Verdict::holds);
  }

  // ---- 1. completo ----

  static Verdict complete(List<Stored> truth, List<ClientLog> clients) {
    Counter c = new Counter();
    long lastSeq = truth.stream().mapToLong(Stored::seq).max().orElse(0);

    Set<Long> inTruth = new HashSet<>();
    truth.forEach(m -> inTruth.add(m.seq()));
    for (long seq = 1; seq <= lastSeq; seq++) {
      if (!inTruth.contains(seq)) {
        c.add("el servidor tiene un hueco: falta el seq " + seq);
      }
    }
    for (int i = 0; i < clients.size(); i++) {
      Set<Long> mine = new HashSet<>();
      clients.get(i).messages().forEach(m -> mine.add(m.seq()));
      for (long seq = 1; seq <= lastSeq; seq++) {
        if (!mine.contains(seq)) {
          c.add("el cliente %d no tiene el seq %d (de 1 a %d)".formatted(i, seq, lastSeq));
        }
      }
    }
    return c.verdict("Completo");
  }

  // ---- 2. sin duplicados ----

  static Verdict noDuplicates(List<Stored> truth, List<ClientLog> clients) {
    Counter c = new Counter();
    duplicatesIn(truth, "servidor", c);
    for (int i = 0; i < clients.size(); i++) {
      ClientLog log = clients.get(i);
      for (String conflict : log.conflicts()) {
        c.add("cliente %d: %s".formatted(i, conflict));
      }
      duplicatesIn(log.messages(), "cliente " + i, c);
    }
    return c.verdict("Sin duplicados");
  }

  private static void duplicatesIn(List<Stored> messages, String where, Counter c) {
    Map<UUID, Long> seqOf = new HashMap<>();
    Set<Long> seqs = new HashSet<>();
    for (Stored m : messages) {
      if (!seqs.add(m.seq())) {
        c.add("%s: el seq %d aparece dos veces".formatted(where, m.seq()));
      }
      Long previous = seqOf.putIfAbsent(m.clientMsgId(), m.seq());
      if (previous != null) {
        c.add(
            "%s: el mensaje %s aparece con dos seq, %d y %d"
                .formatted(where, m.clientMsgId(), previous, m.seq()));
      }
    }
  }

  // ---- 3. mismo orden ----

  static Verdict sameOrder(List<Stored> truth, List<ClientLog> clients) {
    Counter c = new Counter();
    List<UUID> expected = truth.stream().sorted(bySeq()).map(Stored::clientMsgId).toList();
    for (int i = 0; i < clients.size(); i++) {
      List<UUID> mine =
          clients.get(i).messages().stream().sorted(bySeq()).map(Stored::clientMsgId).toList();
      int common = Math.min(expected.size(), mine.size());
      for (int pos = 0; pos < common; pos++) {
        if (!expected.get(pos).equals(mine.get(pos))) {
          c.add("el cliente %d difiere del servidor en la posición %d".formatted(i, pos + 1));
        }
      }
      long lengthDiff = Math.abs((long) expected.size() - mine.size());
      for (long k = 0; k < lengthDiff; k++) {
        c.add(
            "el cliente %d tiene %d mensajes y el servidor %d"
                .formatted(i, mine.size(), expected.size()));
      }
    }
    return c.verdict("Mismo orden");
  }

  private static java.util.Comparator<Stored> bySeq() {
    return java.util.Comparator.comparingLong(Stored::seq);
  }

  // ---- 4. fiel ----

  static Verdict faithful(List<Stored> truth, Ledger ledger, Set<UUID> planned) {
    Counter c = new Counter();
    Map<UUID, List<Long>> seqsOf = new HashMap<>();
    for (Stored m : truth) {
      seqsOf.computeIfAbsent(m.clientMsgId(), k -> new ArrayList<>()).add(m.seq());
    }

    for (Confirmed ok : ledger.confirmedSends().values()) {
      List<Long> found = seqsOf.getOrDefault(ok.clientMsgId(), List.of());
      if (found.size() != 1) {
        c.add(
            "el envío confirmado %s aparece %d veces en el servidor"
                .formatted(ok.clientMsgId(), found.size()));
      } else if (found.get(0) != ok.seq()) {
        c.add(
            "el envío %s se confirmó con seq %d pero está en %d"
                .formatted(ok.clientMsgId(), ok.seq(), found.get(0)));
      }
    }
    for (UUID unconfirmed : ledger.unconfirmed()) {
      int times = seqsOf.getOrDefault(unconfirmed, List.of()).size();
      if (times > 1) {
        c.add("el envío sin confirmar %s aparece %d veces".formatted(unconfirmed, times));
      }
    }
    for (UUID id : seqsOf.keySet()) {
      if (!planned.contains(id)) {
        c.add("aparece un mensaje que nadie envió: " + id);
      }
    }
    for (Replay r : ledger.replays()) {
      if (r.seqNow() != r.originalSeq() || r.statusNow() != 200) {
        c.add(
            "reenviar %s devolvió %d con seq %d; se esperaba 200 con seq %d"
                .formatted(r.clientMsgId(), r.statusNow(), r.seqNow(), r.originalSeq()));
      }
    }
    return c.verdict("Fiel");
  }

  /** Cuenta violaciones y guarda las primeras como ejemplo. */
  private static final class Counter {
    private long count;
    private final List<String> examples = new ArrayList<>();

    void add(String what) {
      count++;
      if (examples.size() < MAX_EXAMPLES) {
        examples.add(what);
      }
    }

    Verdict verdict(String name) {
      return new Verdict(name, count, List.copyOf(examples));
    }
  }
}
