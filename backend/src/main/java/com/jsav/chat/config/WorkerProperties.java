package com.jsav.chat.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El worker de notificaciones (ADR 0013). {@code retryDelays} son las esperas entre un intento
 * fallido y el siguiente, de menor a mayor: tras el último fallo el evento va a la cola de mensajes
 * muertos. Con tres esperas hay cuatro intentos en total.
 */
@ConfigurationProperties(prefix = "app.worker")
public record WorkerProperties(List<Duration> retryDelays) {

  public WorkerProperties {
    if (retryDelays == null || retryDelays.isEmpty()) {
      throw new IllegalArgumentException("app.worker.retry-delays necesita al menos una espera");
    }
    Duration previous = Duration.ZERO;
    for (Duration delay : retryDelays) {
      if (delay.compareTo(previous) <= 0) {
        // «Espera creciente»; además, la cola de cada espera lleva sus milisegundos en el nombre.
        throw new IllegalArgumentException(
            "app.worker.retry-delays debe ser una lista creciente de esperas positivas");
      }
      previous = delay;
    }
    retryDelays = List.copyOf(retryDelays);
  }

  /** Cuántas veces se intenta un evento antes de mandarlo a la cola de mensajes muertos. */
  public int maxAttempts() {
    return retryDelays.size() + 1;
  }
}
