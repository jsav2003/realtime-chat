package com.jsav.chat.harness;

import com.jsav.chat.harness.ScenarioPlan.Send;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A quién menciona cada envío del plan. Es el plan de {@link ScenarioPlan} con un {@code @usuario}
 * (o dos) agregado al cuerpo de una parte de los mensajes. Con la misma semilla sale siempre lo
 * mismo, y nunca se menciona a quien escribe: el servidor no le avisa a nadie de lo que escribe él.
 *
 * <p>Lo que se menciona acá es lo que el servidor tiene que acabar notificando, una vez cada
 * mención, aunque el worker muera en el camino.
 */
public final class MentionPlan {

  private final List<List<Send>> sendsByClient;
  private final Map<UUID, List<Integer>> mentioned;

  private MentionPlan(List<List<Send>> sendsByClient, Map<UUID, List<Integer>> mentioned) {
    this.sendsByClient = sendsByClient;
    this.mentioned = mentioned;
  }

  /**
   * @param usernames el username de cada cliente, en el orden de los clientes
   * @param mentionRate qué fracción de los mensajes menciona a alguien (de 0 a 1)
   */
  public static MentionPlan generate(
      ScenarioPlan plan, List<String> usernames, double mentionRate) {
    Scenario sc = plan.scenario();
    List<List<Send>> sendsByClient = new ArrayList<>();
    Map<UUID, List<Integer>> mentioned = new HashMap<>();
    for (int client = 0; client < sc.clients(); client++) {
      List<Send> mine = new ArrayList<>();
      for (Send send : plan.sendsOf(client)) {
        // Cada envío tiene su propio generador: cambiar un cliente no mueve a los demás. La semilla
        // se mezcla antes de usarla: con semillas casi consecutivas, la primera salida de dos
        // java.util.Random es casi la misma y las menciones saldrían en bloques por cliente.
        Random rnd = new Random(mix(mix(mix(sc.seed()) ^ client) ^ send.index()));
        List<Integer> who = List.of();
        String body = send.body();
        if (rnd.nextDouble() < mentionRate) {
          List<Integer> others = new ArrayList<>();
          for (int c = 0; c < sc.clients(); c++) {
            if (c != client) {
              others.add(c);
            }
          }
          Collections.shuffle(others, rnd);
          who = List.copyOf(others.subList(0, Math.min(1 + rnd.nextInt(2), others.size())));
          body =
              body
                  + " "
                  + who.stream().map(i -> "@" + usernames.get(i)).collect(Collectors.joining(" "));
        }
        mine.add(
            new Send(
                send.client(),
                send.index(),
                send.clientMsgId(),
                body,
                send.pauseMillis(),
                send.resendAfterConfirm()));
        mentioned.put(send.clientMsgId(), who);
      }
      sendsByClient.add(List.copyOf(mine));
    }
    return new MentionPlan(List.copyOf(sendsByClient), Map.copyOf(mentioned));
  }

  /** SplitMix64: reparte los bits de una semilla para que valores vecinos queden muy lejos. */
  private static long mix(long x) {
    long z = x + 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Los envíos de ese cliente, con los {@code @usuario} ya puestos en el cuerpo. */
  public List<Send> sendsOf(int client) {
    return sendsByClient.get(client);
  }

  /** Los clientes (por índice) a los que menciona ese envío; vacío si no menciona a nadie. */
  public List<Integer> mentionedBy(UUID clientMsgId) {
    return mentioned.getOrDefault(clientMsgId, List.of());
  }

  /** Cuántas menciones hay en todo el plan (suma de personas mencionadas por mensaje). */
  public long plannedMentions() {
    return mentioned.values().stream().mapToLong(List::size).sum();
  }
}
