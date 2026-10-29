package com.jsav.chat.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lo que el servidor le confirmó a los emisores. Es la mitad de la verdad que necesita el
 * invariante 4 ("fiel"): todo envío confirmado debe aparecer exactamente una vez y con el seq que
 * se confirmó, y nada sin confirmar puede aparecer duplicado.
 */
public final class Ledger {

  /** Un envío confirmado (201 o 200) y el seq que el servidor le dio. */
  public record Confirmed(UUID clientMsgId, long seq, int status) {}

  /** Un reenvío deliberado de un mensaje ya confirmado. */
  public record Replay(UUID clientMsgId, long originalSeq, long seqNow, int statusNow) {}

  private final Map<UUID, Confirmed> confirmed = new ConcurrentHashMap<>();
  private final List<Replay> replays = Collections.synchronizedList(new ArrayList<>());
  private final List<UUID> unconfirmed = Collections.synchronizedList(new ArrayList<>());
  private final AtomicLong retriedRequests = new AtomicLong();
  private final AtomicLong rateLimitedRequests = new AtomicLong();

  /** Marca un envío como confirmado. Confirmarlo con dos seq distintos sería un fallo grave. */
  public void confirmed(UUID clientMsgId, long seq, int status) {
    Confirmed previous =
        confirmed.putIfAbsent(clientMsgId, new Confirmed(clientMsgId, seq, status));
    if (previous != null && previous.seq() != seq) {
      throw new IllegalStateException(
          "%s confirmado con dos seq: %d y %d".formatted(clientMsgId, previous.seq(), seq));
    }
  }

  public void replayed(UUID clientMsgId, long originalSeq, long seqNow, int statusNow) {
    replays.add(new Replay(clientMsgId, originalSeq, seqNow, statusNow));
  }

  /** Un envío que se rindió sin confirmación: puede o no haberse guardado. */
  public void gaveUp(UUID clientMsgId) {
    unconfirmed.add(clientMsgId);
  }

  public void retriedRequest() {
    retriedRequests.incrementAndGet();
  }

  /** El servidor frenó un envío con 429 (límite de mensajes): se esperó y se reintentó. */
  public void rateLimitedRequest() {
    rateLimitedRequests.incrementAndGet();
  }

  public Map<UUID, Confirmed> confirmedSends() {
    return Map.copyOf(confirmed);
  }

  public List<Replay> replays() {
    synchronized (replays) {
      return List.copyOf(replays);
    }
  }

  public List<UUID> unconfirmed() {
    synchronized (unconfirmed) {
      return List.copyOf(unconfirmed);
    }
  }

  public long retriedRequests() {
    return retriedRequests.get();
  }

  public long rateLimitedRequests() {
    return rateLimitedRequests.get();
  }
}
