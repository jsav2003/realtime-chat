package com.jsav.chat.harness;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Los parámetros de una corrida. Con la misma semilla se genera exactamente el mismo plan (qué
 * manda cada cliente, qué reenvía, a quién se desconecta y cuándo se reinicia una instancia); lo
 * que no se puede fijar es el orden en que el planificador del sistema operativo intercala los
 * hilos, y por eso las invariantes se comprueban sobre el resultado y no sobre la traza.
 */
public record Scenario(
    long seed,
    int clients,
    int messagesPerClient,
    double duplicateRate,
    int disconnects,
    double restartAt,
    String lbUrl,
    String instanceAUrl,
    String instanceBUrl) {

  public static final Scenario DEFAULTS =
      new Scenario(
          42,
          8,
          60,
          0.15,
          12,
          0.4,
          "http://localhost:8088",
          "http://localhost:8081",
          "http://localhost:8082");

  private static final Set<String> OPTIONS =
      Set.of(
          "seed", "clients", "messages", "duplicates", "disconnects", "restart-at", "lb", "a", "b");

  public int totalMessages() {
    return clients * messagesPerClient;
  }

  public String urlOf(Instance instance) {
    return instance == Instance.A ? instanceAUrl : instanceBUrl;
  }

  /** Lee {@code --clave valor}; lo que no se pasa queda en {@link #DEFAULTS}. */
  public static Scenario fromArgs(String[] args) {
    Map<String, String> given = new HashMap<>();
    for (int i = 0; i + 1 < args.length; i += 2) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Argumento inesperado: " + args[i]);
      }
      given.put(args[i].substring(2), args[i + 1]);
    }
    if (!OPTIONS.containsAll(given.keySet())) {
      throw new IllegalArgumentException("Opciones conocidas: " + OPTIONS + "; recibí " + given);
    }
    Scenario d = DEFAULTS;
    Scenario s =
        new Scenario(
            Long.parseLong(given.getOrDefault("seed", String.valueOf(d.seed))),
            Integer.parseInt(given.getOrDefault("clients", String.valueOf(d.clients))),
            Integer.parseInt(given.getOrDefault("messages", String.valueOf(d.messagesPerClient))),
            Double.parseDouble(given.getOrDefault("duplicates", String.valueOf(d.duplicateRate))),
            Integer.parseInt(given.getOrDefault("disconnects", String.valueOf(d.disconnects))),
            Double.parseDouble(given.getOrDefault("restart-at", String.valueOf(d.restartAt))),
            given.getOrDefault("lb", d.lbUrl),
            given.getOrDefault("a", d.instanceAUrl),
            given.getOrDefault("b", d.instanceBUrl));
    if (s.clients < 2 || s.messagesPerClient < 1 || s.restartAt < 0 || s.restartAt > 1) {
      throw new IllegalArgumentException("clients >= 2, messages >= 1 y restart-at entre 0 y 1");
    }
    return s;
  }
}
