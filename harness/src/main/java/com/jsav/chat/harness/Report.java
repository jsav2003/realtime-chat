package com.jsav.chat.harness;

import com.jsav.chat.harness.Invariants.Verdict;
import java.util.List;
import java.util.Locale;

/** El informe de una corrida en Markdown, listo para pegar en docs/DELIVERY.md. */
public final class Report {

  private Report() {}

  public static String markdown(RunSummary r, List<Verdict> verdicts) {
    Scenario sc = r.scenario();
    StringBuilder md = new StringBuilder();
    md.append("### Corrida con semilla %d\n\n".formatted(sc.seed()));
    md.append(
        String.format(
            Locale.ROOT,
            "%d clientes × %d mensajes, %.0f %% reenviados a propósito, %d desconexiones, reinicio al %.0f %% de la corrida, hasta %d ms entre envíos.\n\n",
            sc.clients(),
            sc.messagesPerClient(),
            sc.duplicateRate() * 100,
            sc.disconnects(),
            sc.restartAt() * 100,
            sc.paceMillis()));

    md.append("| Medida | Valor |\n|---|---|\n");
    row(md, "Envíos planeados", r.plannedSends());
    row(md, "Envíos confirmados por el servidor", r.confirmedSends());
    row(md, "Envíos que se rindieron sin confirmación", r.unconfirmedSends());
    row(md, "Reenvíos de mensajes ya confirmados (mismo `client_msg_id`)", r.forcedResends());
    row(md, "Solicitudes reintentadas por fallas (red, 5xx)", r.retriedRequests());
    row(md, "Desconexiones inyectadas", r.disconnectsInjected());
    row(md, "Reinicio de instancia", restartText(r));
    row(md, "Mensajes en el servidor / `last_seq`", r.serverMessages() + " / " + r.lastSeq());
    row(md, "Primera vez que un cliente vio un mensaje: en vivo", r.firstSeenLive());
    row(
        md,
        "Primera vez que un cliente vio un mensaje: por resincronización",
        r.firstSeenByResync());
    row(md, "Entregas repetidas descartadas por `seq`", r.repeatedDeliveries());
    row(
        md,
        "Mensajes que faltaban en los clientes justo antes del resync final",
        r.missingBeforeFinalResync());
    row(
        md,
        "Conexiones STOMP abiertas / resincronizaciones",
        r.totalConnects() + " / " + r.totalResyncs());
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

  private static String restartText(RunSummary r) {
    if (r.restartError() != null) {
      return "falló: " + r.restartError();
    }
    if (r.restartedInstance() == null) {
      return "no llegó a ocurrir";
    }
    return String.format(
        Locale.ROOT,
        "%s (volvió a responder en %.1f s)",
        r.restartedInstance().service,
        r.restartMillis() / 1000.0);
  }

  private static void row(StringBuilder md, String name, Object value) {
    md.append("| ").append(name).append(" | ").append(value).append(" |\n");
  }
}
