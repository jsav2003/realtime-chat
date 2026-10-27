package com.jsav.chat.harness;

/**
 * Cuántos envíos se han confirmado. El caos se dispara por este contador y no por reloj, así una
 * semilla provoca las mismas fallas en los mismos puntos aunque la máquina sea lenta.
 */
public final class Progress {

  private int confirmed;
  private boolean finished;

  public synchronized void oneConfirmed() {
    confirmed++;
    notifyAll();
  }

  /** Los emisores terminaron: despierta a quien siga esperando. */
  public synchronized void finish() {
    finished = true;
    notifyAll();
  }

  public synchronized int confirmed() {
    return confirmed;
  }

  /** Espera a que haya al menos {@code n} confirmados. Devuelve false si el arnés terminó antes. */
  public synchronized boolean awaitAtLeast(int n) throws InterruptedException {
    while (confirmed < n && !finished) {
      wait(500);
    }
    return confirmed >= n;
  }
}
