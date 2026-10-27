package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.ApiException;
import com.jsav.chat.harness.ApiClient.SendResult;
import com.jsav.chat.harness.ScenarioPlan.Send;
import java.util.List;
import java.util.UUID;

/**
 * Manda los mensajes de un cliente, uno tras otro, como el compositor del frontend: si algo falla
 * (red caída, instancia reiniciándose, 5xx) reintenta con el mismo {@code clientMsgId}, y algunos
 * mensajes ya confirmados los vuelve a mandar a propósito para comprobar que no se duplican.
 */
public final class Sender implements Runnable {

  /** Una llamada de envío; en la corrida real es {@link ApiClient#send}. */
  @FunctionalInterface
  public interface SendCall {
    SendResult send(UUID clientMsgId, String body);
  }

  static final int MAX_ATTEMPTS = 80;
  static final long RETRY_PAUSE_MILLIS = 250;

  private final List<Send> plan;
  private final SendCall call;
  private final Ledger ledger;
  private final Progress progress;
  private final long retryPauseMillis;

  public Sender(List<Send> plan, SendCall call, Ledger ledger, Progress progress) {
    this(plan, call, ledger, progress, RETRY_PAUSE_MILLIS);
  }

  Sender(List<Send> plan, SendCall call, Ledger ledger, Progress progress, long retryPauseMillis) {
    this.plan = plan;
    this.call = call;
    this.ledger = ledger;
    this.progress = progress;
    this.retryPauseMillis = retryPauseMillis;
  }

  @Override
  public void run() {
    for (Send send : plan) {
      pause(send.pauseMillis());
      SendResult first = sendWithRetry(send);
      if (first == null) {
        ledger.gaveUp(send.clientMsgId());
        progress.oneConfirmed(); // el progreso cuenta intentos terminados, no solo éxitos
        continue;
      }
      ledger.confirmed(send.clientMsgId(), first.message().seq(), first.status());
      progress.oneConfirmed();
      if (send.resendAfterConfirm()) {
        SendResult again = sendWithRetry(send);
        if (again != null) {
          ledger.replayed(
              send.clientMsgId(), first.message().seq(), again.message().seq(), again.status());
        }
      }
    }
  }

  /** {@code null} si se rindió: un 4xx no se reintenta porque no va a cambiar. */
  private SendResult sendWithRetry(Send send) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return call.send(send.clientMsgId(), send.body());
      } catch (ApiException e) {
        if (e.status < 500) {
          return null;
        }
      } catch (IllegalStateException e) {
        // sin respuesta: la instancia se está reiniciando o la red falló
      }
      ledger.retriedRequest();
      pause(retryPauseMillis);
    }
    return null;
  }

  private static void pause(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
