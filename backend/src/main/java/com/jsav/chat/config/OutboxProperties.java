package com.jsav.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El publicador de la outbox (ADR 0012): cada {@code pollInterval} lleva a RabbitMQ hasta {@code
 * batchSize} eventos pendientes y espera hasta {@code confirmTimeout} a que el broker los confirme.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(Duration pollInterval, int batchSize, Duration confirmTimeout) {

  public OutboxProperties {
    if (pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("app.outbox.poll-interval debe ser positivo");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("app.outbox.batch-size debe ser al menos 1");
    }
    if (confirmTimeout.isZero() || confirmTimeout.isNegative()) {
      throw new IllegalArgumentException("app.outbox.confirm-timeout debe ser positivo");
    }
  }
}
