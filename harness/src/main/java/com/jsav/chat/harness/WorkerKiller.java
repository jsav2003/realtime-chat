package com.jsav.chat.harness;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mata al worker de notificaciones mientras los clientes envían (DESIGN.md §8.3): a ciertos
 * progresos de la corrida (envíos ya confirmados), según el plan sembrado, le manda SIGKILL al
 * contenedor, espera un rato con el worker muerto y lo vuelve a levantar. Un SIGKILL no deja cerrar
 * nada: lo que el worker tenía a medias, sin confirmar a RabbitMQ, vuelve a la cola.
 */
public final class WorkerKiller {

  /** Qué se le hace al contenedor del worker; en la corrida real, {@code docker compose}. */
  public interface Container {
    void kill() throws Exception;

    void start() throws Exception;
  }

  /** Matar al worker cuando se hayan confirmado {@code atConfirmed} envíos, y tenerlo caído. */
  public record Kill(int atConfirmed, long downMillis) {}

  private final List<Kill> plan;
  private final Progress progress;
  private final Container worker;
  private final AtomicInteger kills = new AtomicInteger();
  private volatile String error;
  private Thread driver;

  public WorkerKiller(List<Kill> plan, Progress progress, Container worker) {
    this.plan = plan;
    this.progress = progress;
    this.worker = worker;
  }

  /**
   * Reparte {@code kills} muertes al azar a lo largo de los {@code totalSends} envíos, con la misma
   * semilla siempre el mismo plan. Cada una deja al worker caído entre 0,3 y 3 s.
   */
  public static List<Kill> plan(long seed, int kills, int totalSends) {
    Random rnd = new Random(seed ^ 0x5DEECE66DL);
    List<Integer> points = new ArrayList<>();
    for (int i = 0; i < kills; i++) {
      points.add(rnd.nextInt(Math.max(1, totalSends)));
    }
    Collections.sort(points);
    List<Kill> plan = new ArrayList<>();
    for (int point : points) {
      plan.add(new Kill(point, 300 + rnd.nextInt(2700)));
    }
    return List.copyOf(plan);
  }

  /** Arranca el hilo que mata al worker a medida que avanza la corrida. */
  public void start() {
    driver = new Thread(this::run, "worker-killer");
    driver.setDaemon(true);
    driver.start();
  }

  /** Espera a que termine lo que se disparó y se asegura de dejar al worker levantado. */
  public void finish() throws InterruptedException {
    if (driver != null) {
      driver.join(TimeUnit.MINUTES.toMillis(3));
    }
    try {
      worker.start();
    } catch (Exception e) {
      error = "no se pudo dejar al worker levantado: " + e;
    }
  }

  /** Cuántas veces se mató al worker. */
  public int kills() {
    return kills.get();
  }

  /** El motivo si algo falló; null si no. */
  public String error() {
    return error;
  }

  private void run() {
    try {
      for (Kill k : plan) {
        if (!progress.awaitAtLeast(k.atConfirmed())) {
          return; // los emisores terminaron antes de llegar a este punto
        }
        try {
          worker.kill();
          kills.incrementAndGet();
          Thread.sleep(k.downMillis());
          worker.start();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (Exception e) {
          error = e.toString();
          return;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * El contenedor {@code worker} del cluster: {@code docker compose --profile cluster kill|start}.
   */
  public static Container compose(Path composeDir) {
    return new Container() {
      @Override
      public void kill() throws Exception {
        run("kill");
      }

      @Override
      public void start() throws Exception {
        run("start");
      }

      private void run(String action) throws IOException, InterruptedException {
        Process p =
            new ProcessBuilder("docker", "compose", "--profile", "cluster", action, "worker")
                .directory(composeDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (p.waitFor() != 0) {
          throw new IOException("docker compose " + action + " worker falló");
        }
      }
    };
  }
}
