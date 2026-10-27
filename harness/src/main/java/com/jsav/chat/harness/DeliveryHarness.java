package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.Account;
import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.Invariants.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Arnés de entrega (DESIGN.md §8.1): N clientes STOMP contra las dos instancias del backend. Todos
 * envían al mismo canal a la vez, se desconectan y reconectan al azar (a veces a la otra
 * instancia), reenvían mensajes ya confirmados y, a mitad de la corrida, se reinicia una instancia.
 * Al terminar comprueba las cuatro invariantes y sale con código distinto de cero si alguna falla.
 */
public final class DeliveryHarness {

  /** Lo que queda al terminar: los números, el veredicto de cada invariante y el informe. */
  public record Outcome(RunSummary summary, List<Verdict> verdicts, String report) {}

  private DeliveryHarness() {}

  public static void main(String[] args) throws Exception {
    Scenario scenario = Scenario.fromArgs(args);
    Path composeDir = Path.of(System.getProperty("harness.compose.dir", "."));
    Outcome outcome = run(scenario, InstanceRestarter.docker(composeDir, scenario));
    System.out.println(outcome.report());
    System.exit(Invariants.allHold(outcome.verdicts()) ? 0 : 1);
  }

  public static Outcome run(Scenario sc, InstanceRestarter restarter) throws Exception {
    long started = System.nanoTime();
    ScenarioPlan plan = ScenarioPlan.generate(sc);
    ApiClient api = new ApiClient(sc.lbUrl());

    System.err.println("Preparando " + sc.clients() + " usuarios, un servidor y un canal…");
    Setup world = Setup.create(api, sc);

    List<SimulatedClient> clients = new ArrayList<>();
    for (int i = 0; i < sc.clients(); i++) {
      SimulatedClient c =
          new SimulatedClient(i, world.accounts().get(i), world.channelId(), sc, api);
      c.connect(plan.startInstanceOf(i));
      clients.add(c);
    }
    pause(1500); // que todos queden suscritos antes de empezar

    Progress progress = new Progress();
    Ledger ledger = new Ledger();
    Chaos chaos = new Chaos(plan, clients, progress, restarter);
    chaos.start();

    System.err.println("Enviando " + sc.totalMessages() + " mensajes con fallas inyectadas…");
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
    chaos.finish();

    // Lo que ya está en vuelo termina de llegar; lo que no, lo recupera la resincronización.
    pause(2000);
    for (int i = 0; i < clients.size(); i++) {
      if (!clients.get(i).isConnected()) {
        clients.get(i).connect(plan.startInstanceOf(i));
      }
    }
    pause(1500);

    List<Stored> truth = api.listAfter(world.accounts().get(0), world.channelId(), 0);
    long lastSeq = truth.stream().mapToLong(Stored::seq).max().orElse(0);
    long missingBefore = 0;
    for (SimulatedClient c : clients) {
      missingBefore += Math.max(0, lastSeq - c.log().messages().size());
    }

    System.err.println("Resincronización final y verificación…");
    for (SimulatedClient c : clients) {
      c.resyncBlocking();
    }

    Set<UUID> planned = new HashSet<>();
    for (int i = 0; i < sc.clients(); i++) {
      plan.sendsOf(i).forEach(s -> planned.add(s.clientMsgId()));
    }
    List<ClientLog> logs = clients.stream().map(SimulatedClient::log).toList();
    List<Verdict> verdicts = Invariants.check(truth, logs, ledger, planned);

    RunSummary summary =
        new RunSummary(
            sc,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            sc.totalMessages(),
            ledger.confirmedSends().size(),
            ledger.unconfirmed().size(),
            ledger.replays().size(),
            ledger.retriedRequests(),
            chaos.disconnectsInjected(),
            chaos.restartedInstance(),
            chaos.restartMillis(),
            chaos.restartError(),
            truth.size(),
            lastSeq,
            logs.stream().mapToLong(l -> l.countFirstSeenBy(ClientLog.Source.LIVE)).sum(),
            logs.stream().mapToLong(l -> l.countFirstSeenBy(ClientLog.Source.RESYNC)).sum(),
            logs.stream().mapToLong(ClientLog::repeatedDeliveries).sum(),
            missingBefore,
            clients.stream().mapToInt(SimulatedClient::connects).sum(),
            clients.stream().mapToInt(SimulatedClient::resyncs).sum(),
            hardware());
    clients.forEach(SimulatedClient::close);
    return new Outcome(summary, verdicts, Report.markdown(summary, verdicts));
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
