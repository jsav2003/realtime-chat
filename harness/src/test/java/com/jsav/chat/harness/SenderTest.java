package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.ApiClient.SendResult;
import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.ScenarioPlan.Send;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SenderTest {

  private final Ledger ledger = new Ledger();
  private final Progress progress = new Progress();

  private static Send send(int i, boolean resend) {
    return new Send(0, i, UUID.nameUUIDFromBytes(("s" + i).getBytes()), "m" + i, 0, resend);
  }

  /** Un servidor de mentira: asigna seq consecutivos y reconoce los reintentos. */
  private static final class FakeServer {
    final Map<UUID, Long> seqs = new HashMap<>();
    final List<UUID> calls = new ArrayList<>();

    SendResult send(UUID id, String body) {
      calls.add(id);
      Long existing = seqs.get(id);
      if (existing != null) {
        return new SendResult(200, new Stored(existing, id, body));
      }
      long seq = seqs.size() + 1;
      seqs.put(id, seq);
      return new SendResult(201, new Stored(seq, id, body));
    }
  }

  @Test
  void confirmsEverySend_andCountsProgress() {
    FakeServer server = new FakeServer();
    Sender sender =
        new Sender(List.of(send(0, false), send(1, false)), server::send, ledger, progress, 0);

    sender.run();

    assertThat(ledger.confirmedSends()).hasSize(2);
    assertThat(progress.confirmed()).isEqualTo(2);
    assertThat(ledger.unconfirmed()).isEmpty();
  }

  @Test
  void retriesWithTheSameClientMsgId_untilItWorks() {
    FakeServer server = new FakeServer();
    AtomicInteger failures = new AtomicInteger(2);
    Sender.SendCall flaky =
        (id, body) -> {
          if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("sin respuesta");
          }
          return server.send(id, body);
        };

    new Sender(List.of(send(0, false)), flaky, ledger, progress, 0).run();

    assertThat(ledger.confirmedSends()).hasSize(1);
    assertThat(ledger.retriedRequests()).isEqualTo(2);
    assertThat(server.seqs).hasSize(1);
  }

  @Test
  void a5xxIsRetried_butA4xxIsNot() {
    AtomicInteger calls = new AtomicInteger();
    Sender.SendCall always404 =
        (id, body) -> {
          calls.incrementAndGet();
          throw new ApiClient.ApiException(404, "no existe");
        };

    new Sender(List.of(send(0, false)), always404, ledger, progress, 0).run();

    assertThat(calls.get()).isEqualTo(1);
    assertThat(ledger.unconfirmed()).hasSize(1);
    assertThat(ledger.confirmedSends()).isEmpty();
  }

  @Test
  void givesUpAfterTheMaximumAttempts() {
    AtomicInteger calls = new AtomicInteger();
    Sender.SendCall dead =
        (id, body) -> {
          calls.incrementAndGet();
          throw new ApiClient.ApiException(502, "bad gateway");
        };

    new Sender(List.of(send(0, false)), dead, ledger, progress, 0).run();

    assertThat(calls.get()).isEqualTo(Sender.MAX_ATTEMPTS);
    assertThat(ledger.unconfirmed()).hasSize(1);
  }

  @Test
  void a429IsWaitedOut_andRetriedWithTheSameClientMsgId() {
    FakeServer server = new FakeServer();
    AtomicInteger limited = new AtomicInteger(3);
    Sender.SendCall limiter =
        (id, body) -> {
          if (limited.getAndDecrement() > 0) {
            throw new ApiClient.ApiException(429, "muy rápido", 0);
          }
          return server.send(id, body);
        };

    new Sender(List.of(send(0, false)), limiter, ledger, progress, 0).run();

    assertThat(ledger.confirmedSends()).hasSize(1);
    assertThat(ledger.rateLimitedRequests()).isEqualTo(3);
    // Un 429 no es una falla: no cuenta como solicitud reintentada.
    assertThat(ledger.retriedRequests()).isZero();
    assertThat(server.seqs).hasSize(1);
  }

  @Test
  void a429DoesNotSpendTheAttemptsMeantForRealFailures() {
    AtomicInteger calls = new AtomicInteger();
    FakeServer server = new FakeServer();
    // Más 429 que MAX_ATTEMPTS: si gastaran intentos, el envío se rendiría antes de lograrse.
    int limits = Sender.MAX_ATTEMPTS + 20;
    Sender.SendCall limiter =
        (id, body) -> {
          if (calls.incrementAndGet() <= limits) {
            throw new ApiClient.ApiException(429, "muy rápido", 0);
          }
          return server.send(id, body);
        };

    new Sender(List.of(send(0, false)), limiter, ledger, progress, 0).run();

    assertThat(ledger.confirmedSends()).hasSize(1);
    assertThat(ledger.unconfirmed()).isEmpty();
  }

  @Test
  void aLimitThatNeverLiftsEventuallyGivesUp() {
    AtomicInteger calls = new AtomicInteger();
    Sender.SendCall stuck =
        (id, body) -> {
          calls.incrementAndGet();
          throw new ApiClient.ApiException(429, "muy rápido", 0);
        };

    new Sender(List.of(send(0, false)), stuck, ledger, progress, 0).run();

    assertThat(calls.get()).isEqualTo(Sender.MAX_RATE_LIMITED_RETRIES + 1);
    assertThat(ledger.unconfirmed()).hasSize(1);
  }

  @Test
  void aForcedResendIsRecorded_andReturnsTheSameSeq() {
    FakeServer server = new FakeServer();

    new Sender(List.of(send(0, true)), server::send, ledger, progress, 0).run();

    assertThat(ledger.replays()).hasSize(1);
    Ledger.Replay replay = ledger.replays().get(0);
    assertThat(replay.statusNow()).isEqualTo(200);
    assertThat(replay.seqNow()).isEqualTo(replay.originalSeq());
    assertThat(server.seqs).hasSize(1);
  }
}
