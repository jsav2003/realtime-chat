package com.jsav.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Límite de mensajes nuevos por usuario (DESIGN.md §5): un bucket de {@code capacity} tokens que
 * recupera {@code refillPerMinute} cada minuto.
 */
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(int capacity, double refillPerMinute) {

  public RateLimitProperties {
    if (capacity < 1) {
      throw new IllegalArgumentException("app.ratelimit.capacity debe ser al menos 1");
    }
    if (refillPerMinute <= 0) {
      // Sin recarga, quien agota el bucket no podría volver a escribir jamás.
      throw new IllegalArgumentException("app.ratelimit.refill-per-minute debe ser mayor que 0");
    }
  }
}
