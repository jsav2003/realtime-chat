package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.Account;
import com.jsav.chat.harness.ApiClient.NotificationView;
import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.Invariants.Verdict;
import com.jsav.chat.harness.NotificationInvariants.Mention;
import com.jsav.chat.harness.NotificationInvariants.Received;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Arnés de las menciones (DESIGN.md §8.3), el hermano real del test {@code
 * WorkerInterruptedIntegrationTest}: N clientes envían mensajes que mencionan a otros, y mientras
 * tanto se le manda SIGKILL al contenedor del worker varias veces. Al terminar comprueba, contra lo
 * que cada persona recibe por {@code GET /notifications}, que toda mención de un mensaje guardado
 * terminó notificada exactamente una vez y que nadie recibió de más.
 *
 * <p>Necesita el cluster arriba, con el servicio {@code worker} ({@code docker compose --profile
 * cluster up -d --build}); usa el mismo escenario sembrado que el arnés de entrega.
 */
public final class MentionsHarness {

  /** Cuánto se espera, tras el último envío, a que el worker termine de notificar todo. */
  private static final long DRAIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(180);

  /** Lo máximo que el servidor entrega por consulta. */
  private static final int PAGE = 100;

  /**
   * Las opciones de la corrida: el escenario del arnés de entrega más las propias de las menciones.
   */
  public record Options(Scenario scenario, int kills, double mentionRate) {

    static final int DEFAULT_MESSAGES = 40;

    /**
     * Lee {@code --kills N} y {@code --mention-rate X} y le pasa el resto a {@link
     * Scenario#fromArgs}. Sin {@code --messages}, usa 40 por cliente: con 80 y 8 clientes alguien
     * recibiría más menciones de las que el servidor entrega en una consulta.
     */
    public static Options parse(String[] args) {
      int kills = 6;
      double rate = 0.7;
      List<String> rest = new ArrayList<>();
      for (int i = 0; i + 1 < args.length; i += 2) {
        switch (args[i]) {
          case "--kills" -> kills = Integer.parseInt(args[i + 1]);
          case "--mention-rate" -> rate = Double.parseDouble(args[i + 1]);
          default -> {
            rest.add(args[i]);
            rest.add(args[i + 1]);
          }
        }
      }
      if (!rest.contains("--messages")) {
        rest.add("--messages");
        rest.add(String.valueOf(DEFAULT_MESSAGES));
      }
      if (kills < 0 || rate < 0 || rate > 1) {
        throw new IllegalArgumentException("kills >= 0 y mention-rate entre 0 y 1");
      }
      return new Options(Scenario.fromArgs(rest.toArray(String[]::new)), kills, rate);
    }
  }

  /** Los números de la corrida. */
  public record Summary(
      Options options,
      long durationMillis,
      int killsInjected,
      String killerError,
      int storedMessages,
      long expectedMentions,
      long receivedNotifications,
      long forcedResends,
      long rateLimitedRequests,
      boolean drained,
      long drainMillis,
      String hardware) {}

  /** Lo que queda al terminar: los números, el veredicto de cada invariante y el informe. */
  public record Outcome(Summary summary, List<Verdict> verdicts, String report) {}

  private MentionsHarness() {}

  public static void main(String[] args) throws Exception {
    Options options = Options.parse(args);
    Path composeDir = Path.of(System.getProperty("harness.compose.dir", "."));
    Outcome outcome = run(options, WorkerKiller.compose(composeDir));
    System.out.println(outcome.report());
    System.exit(NotificationInvariants.allHold(outcome.verdicts()) ? 0 : 1);
  }

  public static Outcome run(Options options, WorkerKiller.Container worker) throws Exception {
    long started = System.nanoTime();
    Scenario sc = options.scenario();
    ScenarioPlan base = ScenarioPlan.generate(sc);
    ApiClient api = new ApiClient(sc.lbUrl());

    System.err.println("Preparando " + sc.clients() + " usuarios, un servidor y un canal…");
    Setup world = Setup.create(api, sc);
    MentionPlan plan = MentionPlan.generate(base, world.usernames(), options.mentionRate());
    worker.start(); // por si estaba parado; no hace nada si ya corre

    Progress progress = new Progress();
    Ledger ledger = new Ledger();
    WorkerKiller killer =
        new WorkerKiller(
            WorkerKiller.plan(sc.seed(), options.kills(), sc.totalMessages()), progress, worker);
    killer.start();

    System.err.println(
        "Enviando %d mensajes (%d menciones) y matando al worker hasta %d veces…"
            .formatted(sc.totalMessages(), plan.plannedMentions(), options.kills()));
    ExecutorService senders = Executors.newFixedThreadPool(sc.clients());
    for (int i = 0; i < sc.clients(); i++) {
      Account account = world.accounts().get(i);
      senders.execute(
          new Sender(
              plan.sendsOf(i),
              (id, body) -> api.send(account, world.channelId(), id, body),
              ledger,
              progress));
    }
    senders.shutdown();
    if (!senders.awaitTermination(15, TimeUnit.MINUTES)) {
      throw new IllegalStateException("Los emisores no terminaron en 15 minutos");
    }
    progress.finish();
    killer.finish();

    // La verdad: los mensajes que quedaron guardados. Cada uno debe haber notificado a quienes
    // mencionaba, y solo a ellos, haya o no llegado a confirmarse al emisor.
    List<Stored> truth = api.listAfter(world.accounts().get(0), world.channelId(), 0);
    Set<Mention> expected = new HashSet<>();
    for (Stored message : truth) {
      for (int user : plan.mentionedBy(message.clientMsgId())) {
        expected.add(new Mention(message.seq(), user));
      }
    }
    Map<Integer, Long> expectedPerUser = new HashMap<>();
    expected.forEach(m -> expectedPerUser.merge(m.user(), 1L, Long::sum));
    expectedPerUser.forEach(
        (user, count) -> {
          if (count > PAGE) {
            throw new IllegalStateException(
                "El cliente %d recibiría %d menciones y el servidor entrega hasta %d por consulta: baja --messages o --mention-rate"
                    .formatted(user, count, PAGE));
          }
        });

    System.err.println(
        "Esperando a que el worker termine de notificar %d menciones…".formatted(expected.size()));
    long drainStarted = System.nanoTime();
    List<Received> received = fetch(api, world);
    boolean drained = arrived(expected, received);
    while (!drained && millisSince(drainStarted) < DRAIN_TIMEOUT_MILLIS) {
      pause(1000);
      received = fetch(api, world);
      drained = arrived(expected, received);
    }
    long drainMillis = millisSince(drainStarted);
    if (drained) {
      pause(3000); // un momento más, por si todavía iba a aparecer algo de más
      received = fetch(api, world);
    }

    List<Verdict> verdicts = new ArrayList<>(NotificationInvariants.check(expected, received));
    if (options.kills() > 0 && killer.kills() == 0) {
      // Si nunca se mató al worker, un pase no prueba nada: se dice como una violación más.
      verdicts.add(
          new Verdict(
              "Se mató al worker",
              1,
              List.of(
                  "no se pudo matar al worker ni una vez"
                      + (killer.error() == null ? "" : ": " + killer.error()))));
    }

    long receivedCount = received.stream().mapToLong(r -> r.items().size()).sum();
    Summary summary =
        new Summary(
            options,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            killer.kills(),
            killer.error(),
            truth.size(),
            expected.size(),
            receivedCount,
            ledger.replays().size(),
            ledger.rateLimitedRequests(),
            drained,
            drainMillis,
            hardware());
    return new Outcome(summary, verdicts, markdown(summary, verdicts));
  }

  private static List<Received> fetch(ApiClient api, Setup world) {
    List<Received> all = new ArrayList<>();
    for (int user = 0; user < world.accounts().size(); user++) {
      List<NotificationView> items =
          api.listNotifications(world.accounts().get(user), PAGE).items();
      all.add(new Received(user, items));
    }
    return all;
  }

  /** Ya le llegó a cada persona todo lo que se esperaba (puede haber de más; eso lo dirá Fiel). */
  private static boolean arrived(Set<Mention> expected, List<Received> received) {
    Set<Mention> got = new HashSet<>();
    for (Received r : received) {
      r.items().forEach(n -> got.add(new Mention(n.seq(), r.user())));
    }
    return got.containsAll(expected);
  }

  static String markdown(Summary r, List<Verdict> verdicts) {
    Scenario sc = r.options().scenario();
    StringBuilder md = new StringBuilder();
    md.append("### Menciones con semilla %d\n\n".formatted(sc.seed()));
    md.append(
        String.format(
            Locale.ROOT,
            "%d clientes × %d mensajes, %.0f %% con menciones, %.0f %% reenviados a propósito, el worker muerto con SIGKILL hasta %d veces, hasta %d ms entre envíos.\n\n",
            sc.clients(),
            sc.messagesPerClient(),
            r.options().mentionRate() * 100,
            sc.duplicateRate() * 100,
            r.options().kills(),
            sc.paceMillis()));
    md.append("| Medida | Valor |\n|---|---|\n");
    row(md, "Mensajes guardados en el servidor", r.storedMessages());
    row(md, "Menciones esperadas (mensaje, persona)", r.expectedMentions());
    row(md, "Notificaciones recibidas por todas las personas", r.receivedNotifications());
    row(
        md,
        "Veces que se mató al worker",
        r.killsInjected() + " de " + r.options().kills() + errorText(r.killerError()));
    row(md, "Reenvíos de mensajes ya confirmados (no deben notificar otra vez)", r.forcedResends());
    row(
        md,
        "Envíos frenados por el límite de mensajes (429) y reintentados",
        r.rateLimitedRequests());
    row(
        md,
        "Tiempo hasta tener todas las notificaciones, desde el último envío",
        r.drained()
            ? String.format(Locale.ROOT, "%.1f s", r.drainMillis() / 1000.0)
            : "no terminó en %d s".formatted(DRAIN_TIMEOUT_MILLIS / 1000));
    row(md, "Duración", String.format(Locale.ROOT, "%.1f s", r.durationMillis() / 1000.0));
    row(md, "Equipo", r.hardware());

    md.append("\n| Invariante | Violaciones |\n|---|---|\n");
    for (Verdict v : verdicts) {
      md.append("| %s | %d |\n".formatted(v.name(), v.violations()));
    }
    for (Verdict v : verdicts) {
      if (!v.holds()) {
        md.append("\n**%s**, primeros ejemplos:\n".formatted(v.name()));
        v.examples().forEach(e -> md.append("- ").append(e).append('\n'));
      }
    }
    return md.toString();
  }

  private static String errorText(String error) {
    return error == null ? "" : " (error: " + error + ")";
  }

  private static void row(StringBuilder md, String name, Object value) {
    md.append("| ").append(name).append(" | ").append(value).append(" |\n");
  }

  private static long millisSince(long nanoStart) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoStart);
  }

  private static String hardware() {
    return "%s, %d hilos, Java %s"
        .formatted(
            System.getProperty("os.name"),
            Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"));
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
