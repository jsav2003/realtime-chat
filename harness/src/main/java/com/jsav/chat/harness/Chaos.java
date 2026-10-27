package com.jsav.chat.harness;

import com.jsav.chat.harness.ScenarioPlan.Disconnect;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Las fallas que se inyectan mientras los clientes envían: desconexiones al azar y el reinicio de
 * una instancia. Cada una espera a que la corrida llegue a cierto progreso (envíos confirmados),
 * según el plan sembrado, y entonces actúa.
 */
public final class Chaos {

  private final ScenarioPlan plan;
  private final List<SimulatedClient> clients;
  private final Progress progress;
  private final InstanceRestarter restarter;
  private final AtomicInteger disconnectsInjected = new AtomicInteger();
  private final ExecutorService outages = Executors.newCachedThreadPool();
  private Thread disconnectsDriver;
  private Thread restartDriver;

  private volatile Instance restarted;
  private volatile long restartMillis = -1;
  private volatile String restartError;

  public Chaos(
      ScenarioPlan plan,
      List<SimulatedClient> clients,
      Progress progress,
      InstanceRestarter restarter) {
    this.plan = plan;
    this.clients = clients;
    this.progress = progress;
    this.restarter = restarter;
  }

  /** Arranca los hilos que disparan las fallas a medida que avanza la corrida. */
  public void start() {
    disconnectsDriver = daemon("chaos-disconnects", this::runDisconnects);
    restartDriver = daemon("chaos-restart", this::runRestart);
  }

  /** Espera a que termine todo lo que se disparó. */
  public void finish() throws InterruptedException {
    for (Thread t : new Thread[] {disconnectsDriver, restartDriver}) {
      if (t != null) {
        t.join(TimeUnit.MINUTES.toMillis(3));
      }
    }
    outages.shutdown();
    outages.awaitTermination(30, TimeUnit.SECONDS);
  }

  public int disconnectsInjected() {
    return disconnectsInjected.get();
  }

  /** La instancia que se reinició, o null si la corrida terminó antes de llegar al punto. */
  public Instance restartedInstance() {
    return restarted;
  }

  /** Cuánto tardó el reinicio hasta que la instancia volvió a responder; -1 si no hubo. */
  public long restartMillis() {
    return restartMillis;
  }

  /** El motivo si el reinicio falló; null si no falló. */
  public String restartError() {
    return restartError;
  }

  private static Thread daemon(String name, Runnable body) {
    Thread t = new Thread(body, name);
    t.setDaemon(true);
    t.start();
    return t;
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

  private void runRestart() {
    try {
      if (!progress.awaitAtLeast(plan.restart().atConfirmed())) {
        return;
      }
      Instance instance = plan.restart().instance();
      long started = System.nanoTime();
      try {
        restarter.restart(instance);
        restarted = instance;
        restartMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      } catch (Exception e) {
        restartError = e.toString();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
