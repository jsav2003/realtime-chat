package com.jsav.chat.harness;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/** Reinicia una instancia del backend y espera a que vuelva a responder. */
@FunctionalInterface
public interface InstanceRestarter {

  void restart(Instance instance) throws Exception;

  /** El de verdad: {@code docker compose --profile cluster restart backend-x}. */
  static InstanceRestarter docker(Path composeDir, Scenario scenario) {
    return instance -> {
      Process p =
          new ProcessBuilder(
                  "docker", "compose", "--profile", "cluster", "restart", instance.service)
              .directory(composeDir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (p.waitFor() != 0) {
        throw new IOException("docker compose restart " + instance.service + " falló");
      }
      waitUntilHealthy(scenario.urlOf(instance));
    };
  }

  private static void waitUntilHealthy(String baseUrl) throws InterruptedException {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
            .timeout(Duration.ofSeconds(2))
            .build();
    long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        if (http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        // todavía arrancando
      }
      Thread.sleep(500);
    }
    throw new IllegalStateException(baseUrl + " no volvió a responder en 120 s");
  }
}
