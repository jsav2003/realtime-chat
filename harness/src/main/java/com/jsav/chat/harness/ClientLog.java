package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.Stored;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lo que un cliente simulado "tiene": los mensajes por seq, igual que el frontend los une con
 * {@code mergeBySeq}. Además cuenta de dónde llegó cada uno (en vivo o por resincronización) y
 * cuántas entregas repetidas recibió, porque un resultado de "cero violaciones" solo dice algo si
 * se sabe cuánto trabajo hizo cada camino.
 */
public final class ClientLog {

  public enum Source {
    LIVE,
    RESYNC
  }

  private final ConcurrentSkipListMap<Long, Stored> bySeq = new ConcurrentSkipListMap<>();
  private final Map<Long, Source> firstSource = new ConcurrentHashMap<>();
  private final List<String> conflicts = Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong repeatedDeliveries = new AtomicLong();

  /**
   * Registra una entrega. Un seq nuevo se guarda; uno repetido se descarta y se cuenta, salvo que
   * traiga otro mensaje que el ya guardado, que es un conflicto (dos mensajes con el mismo seq).
   */
  public void receive(Stored message, Source source) {
    Stored previous = bySeq.putIfAbsent(message.seq(), message);
    if (previous == null) {
      firstSource.put(message.seq(), source);
      return;
    }
    repeatedDeliveries.incrementAndGet();
    if (!previous.clientMsgId().equals(message.clientMsgId())) {
      conflicts.add(
          "seq %d llegó con %s y con %s"
              .formatted(message.seq(), previous.clientMsgId(), message.clientMsgId()));
    }
  }

  /** El mayor seq que se tiene, o 0. Es el cursor que usa la resincronización. */
  public long lastSeq() {
    return bySeq.isEmpty() ? 0 : bySeq.lastKey();
  }

  /** Los mensajes en orden de seq. */
  public List<Stored> messages() {
    return List.copyOf(bySeq.values());
  }

  public List<String> conflicts() {
    synchronized (conflicts) {
      return List.copyOf(conflicts);
    }
  }

  public long repeatedDeliveries() {
    return repeatedDeliveries.get();
  }

  /** Cuántos mensajes se vieron primero por ese camino. */
  public long countFirstSeenBy(Source source) {
    return firstSource.values().stream().filter(s -> s == source).count();
  }
}
