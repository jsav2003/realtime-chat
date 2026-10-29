package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.Invariants.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportTest {

  private static RunSummary summary(Instance restarted, String restartError) {
    return new RunSummary(
        Scenario.DEFAULTS,
        61_500,
        480,
        480,
        0,
        70,
        12,
        5,
        12,
        restarted,
        8_200,
        restartError,
        480,
        480,
        2_900,
        900,
        150,
        31,
        20,
        14,
        "Windows 11, 12 hilos, Java 21");
  }

  private static final List<Verdict> CLEAN =
      List.of(
          new Verdict("Completo", 0, List.of()),
          new Verdict("Sin duplicados", 0, List.of()),
          new Verdict("Mismo orden", 0, List.of()),
          new Verdict("Fiel", 0, List.of()));

  @Test
  void theReportShowsTheScenarioTheFaultsAndEveryInvariant() {
    String md = Report.markdown(summary(Instance.B, null), CLEAN);

    assertThat(md)
        .contains("semilla 42")
        .contains("8 clientes × 80 mensajes")
        .contains("hasta 700 ms entre envíos")
        .contains("| Desconexiones inyectadas | 12 |")
        .contains("backend-b (volvió a responder en 8.2 s)")
        .contains("| Completo | 0 |")
        .contains("| Sin duplicados | 0 |")
        .contains("| Mismo orden | 0 |")
        .contains("| Fiel | 0 |")
        .contains("en vivo | 2900 |")
        .contains("por resincronización | 900 |");
  }

  @Test
  void aViolationShowsItsExamples() {
    List<Verdict> broken =
        List.of(
            new Verdict("Completo", 2, List.of("el cliente 1 no tiene el seq 3")),
            new Verdict("Sin duplicados", 0, List.of()));

    String md = Report.markdown(summary(Instance.A, null), broken);

    assertThat(md).contains("| Completo | 2 |").contains("el cliente 1 no tiene el seq 3");
  }

  @Test
  void aRestartThatDidNotHappenOrFailedIsSaid() {
    assertThat(Report.markdown(summary(null, null), CLEAN)).contains("no llegó a ocurrir");
    assertThat(Report.markdown(summary(null, "docker no responde"), CLEAN))
        .contains("falló: docker no responde");
  }
}
