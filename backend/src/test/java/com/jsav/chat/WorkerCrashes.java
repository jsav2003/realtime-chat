package com.jsav.chat;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decide cuándo el worker «muere» durante la entrega de un mensaje de la cola. Hay dos momentos que
 * importan:
 *
 * <ul>
 *   <li>{@link Point#BEFORE_PROCESSING}: cae antes de tocar nada. RabbitMQ reentrega y el segundo
 *       intento procesa el evento por primera vez.
 *   <li>{@link Point#AFTER_PROCESSING}: cae después de que la notificación se guardó y antes de
 *       confirmar el mensaje. RabbitMQ reentrega y el segundo intento encuentra la notificación
 *       hecha: es el caso que obliga a deduplicar.
 * </ul>
 *
 * <p>Se puede pedir exacto ({@link #crashNext}) o al azar con semilla fija ({@link #arm}), para que
 * una falla se pueda reproducir.
 */
public class WorkerCrashes {

  public enum Point {
    BEFORE_PROCESSING,
    AFTER_PROCESSING
  }

  private final AtomicInteger beforeBudget = new AtomicInteger();
  private final AtomicInteger afterBudget = new AtomicInteger();
  private final AtomicInteger crashedBefore = new AtomicInteger();
  private final AtomicInteger crashedAfter = new AtomicInteger();

  private final Object lock = new Object();
  private Random random;
  private double probabilityBefore;
  private double probabilityAfter;

  /** Las próximas {@code deliveries} entregas caen en ese punto, sin azar. */
  public void crashNext(Point point, int deliveries) {
    budget(point).set(deliveries);
  }

  /** Cada entrega cae al azar con esas probabilidades; la misma semilla da la misma secuencia. */
  public void arm(long seed, double probabilityBefore, double probabilityAfter) {
    synchronized (lock) {
      this.random = new Random(seed);
      this.probabilityBefore = probabilityBefore;
      this.probabilityAfter = probabilityAfter;
    }
  }

  /** Sin más caídas y con los contadores en cero. */
  public void disarm() {
    synchronized (lock) {
      random = null;
    }
    beforeBudget.set(0);
    afterBudget.set(0);
    crashedBefore.set(0);
    crashedAfter.set(0);
  }

  boolean shouldCrash(Point point) {
    boolean crash = takeFromBudget(budget(point)) || rolls(point);
    if (crash) {
      (point == Point.BEFORE_PROCESSING ? crashedBefore : crashedAfter).incrementAndGet();
    }
    return crash;
  }

  public int crashedBefore() {
    return crashedBefore.get();
  }

  public int crashedAfter() {
    return crashedAfter.get();
  }

  private AtomicInteger budget(Point point) {
    return point == Point.BEFORE_PROCESSING ? beforeBudget : afterBudget;
  }

  private static boolean takeFromBudget(AtomicInteger budget) {
    while (true) {
      int left = budget.get();
      if (left <= 0) {
        return false;
      }
      if (budget.compareAndSet(left, left - 1)) {
        return true;
      }
    }
  }

  private boolean rolls(Point point) {
    synchronized (lock) {
      if (random == null) {
        return false;
      }
      double p = point == Point.BEFORE_PROCESSING ? probabilityBefore : probabilityAfter;
      return random.nextDouble() < p;
    }
  }
}
