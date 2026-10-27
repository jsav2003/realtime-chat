package com.jsav.chat.harness;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Todo lo que va a pasar en una corrida, decidido de antemano a partir de la semilla. Los eventos
 * del caos no se disparan por reloj sino por progreso (cuántos envíos ya se confirmaron), así la
 * misma semilla provoca las mismas fallas en los mismos puntos aunque la máquina sea más lenta.
 */
public final class ScenarioPlan {

  /** Un envío de un cliente. */
  public record Send(
      int client,
      int index,
      UUID clientMsgId,
      String body,
      long pauseMillis,
      boolean resendAfterConfirm) {}

  /** Desconectar a un cliente cuando se hayan confirmado {@code atConfirmed} envíos. */
  public record Disconnect(int atConfirmed, int client, long downMillis, Instance reconnectTo) {}

  /** Reiniciar una instancia cuando se hayan confirmado {@code atConfirmed} envíos. */
  public record Restart(int atConfirmed, Instance instance) {}

  private final Scenario scenario;
  private final List<List<Send>> sends;
  private final List<Disconnect> disconnects;
  private final Restart restart;
  private final List<Instance> startInstance;

  private ScenarioPlan(
      Scenario scenario,
      List<List<Send>> sends,
      List<Disconnect> disconnects,
      Restart restart,
      List<Instance> startInstance) {
    this.scenario = scenario;
    this.sends = sends;
    this.disconnects = disconnects;
    this.restart = restart;
    this.startInstance = startInstance;
  }

  public static ScenarioPlan generate(Scenario sc) {
    Random rnd = new Random(sc.seed());

    List<Instance> start = new ArrayList<>();
    for (int c = 0; c < sc.clients(); c++) {
      start.add(rnd.nextBoolean() ? Instance.A : Instance.B);
    }

    List<List<Send>> sends = new ArrayList<>();
    for (int c = 0; c < sc.clients(); c++) {
      List<Send> mine = new ArrayList<>();
      for (int i = 0; i < sc.messagesPerClient(); i++) {
        mine.add(
            new Send(
                c,
                i,
                messageId(sc.seed(), c, i),
                "c" + c + "-m" + i,
                rnd.nextInt(sc.paceMillis()),
                rnd.nextDouble() < sc.duplicateRate()));
      }
      sends.add(List.copyOf(mine));
    }

    int total = sc.totalMessages();
    List<Disconnect> disconnects = new ArrayList<>();
    for (int k = 0; k < sc.disconnects(); k++) {
      disconnects.add(
          new Disconnect(
              rnd.nextInt(total),
              rnd.nextInt(sc.clients()),
              100 + rnd.nextInt(1400),
              rnd.nextBoolean() ? Instance.A : Instance.B));
    }
    disconnects.sort(Comparator.comparingInt(Disconnect::atConfirmed));

    Restart restart =
        new Restart(
            (int) Math.round(total * sc.restartAt()), rnd.nextBoolean() ? Instance.A : Instance.B);

    return new ScenarioPlan(
        sc, List.copyOf(sends), List.copyOf(disconnects), restart, List.copyOf(start));
  }

  /** El mismo (semilla, cliente, índice) da siempre el mismo clientMsgId. */
  static UUID messageId(long seed, int client, int index) {
    return UUID.nameUUIDFromBytes(
        (seed + ":" + client + ":" + index).getBytes(StandardCharsets.UTF_8));
  }

  public Scenario scenario() {
    return scenario;
  }

  public List<Send> sendsOf(int client) {
    return sends.get(client);
  }

  public List<Disconnect> disconnects() {
    return disconnects;
  }

  public Restart restart() {
    return restart;
  }

  /** A qué instancia se conecta cada cliente al empezar. */
  public Instance startInstanceOf(int client) {
    return startInstance.get(client);
  }

  public long plannedResends() {
    return sends.stream().flatMap(List::stream).filter(Send::resendAfterConfirm).count();
  }
}
