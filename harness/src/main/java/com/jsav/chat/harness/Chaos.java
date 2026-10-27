package com.jsav.chat.harness;

import com.jsav.chat.harness.ScenarioPlan.Disconnect;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Las fallas que se inyectan mientras los clientes envían. Cada una espera a que la corrida llegue
 * a cierto progreso (envíos confirmados), según el plan sembrado, y entonces actúa.
 */
public final class Chaos {

  private final ScenarioPlan plan;
  private final List<SimulatedClient> clients;
  private final Progress progress;
  private final AtomicInteger disconnectsInjected = new AtomicInteger();
  private final ExecutorService outages = Executors.newCachedThreadPool();
  private Thread driver;

  public Chaos(ScenarioPlan plan, List<SimulatedClient> clients, Progress progress) {
    this.plan = plan;
    this.clients = clients;
    this.progress = progress;
  }

  /** Arranca el hilo que va disparando las desconexiones a medida que avanza la corrida. */
  public void start() {
    driver = new Thread(this::runDisconnects, "chaos-disconnects");
    driver.setDaemon(true);
    driver.start();
  }

  /** Espera a que termine todo lo que se disparó. */
  public void finish() throws InterruptedException {
    if (driver != null) {
      driver.join(TimeUnit.MINUTES.toMillis(2));
    }
    outages.shutdown();
    outages.awaitTermination(30, TimeUnit.SECONDS);
  }

  public int disconnectsInjected() {
    return disconnectsInjected.get();
  }

  private void runDisconnects() {
    try {
      for (Disconnect d : plan.disconnects()) {
        if (!progress.awaitAtLeast(d.atConfirmed())) {
          return; // los emisores terminaron antes de llegar a este punto
        }
        outages.execute(() -> outage(d));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** El cliente se queda sin conexión un rato y vuelve, a veces a la otra instancia. */
  private void outage(Disconnect d) {
    SimulatedClient client = clients.get(d.client());
    client.dropConnection();
    disconnectsInjected.incrementAndGet();
    try {
      Thread.sleep(d.downMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    client.connect(d.reconnectTo());
  }
}
