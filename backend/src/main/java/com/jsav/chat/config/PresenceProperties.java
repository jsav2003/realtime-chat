package com.jsav.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Presencia (DESIGN.md §5, ADR 0008): cada conexión vive {@code ttl} después de su último latido, y
 * cada instancia renueva las suyas cada {@code refreshInterval}.
 */
@ConfigurationProperties(prefix = "app.presence")
public record PresenceProperties(Duration ttl, Duration refreshInterval) {

  public PresenceProperties {
    if (refreshInterval.isZero() || refreshInterval.isNegative()) {
      throw new IllegalArgumentException("app.presence.refresh-interval debe ser positivo");
    }
    if (ttl.compareTo(refreshInterval.multipliedBy(2)) < 0) {
      // Con menos, un solo latido perdido apagaría a alguien que sigue conectado.
      throw new IllegalArgumentException(
          "app.presence.ttl debe ser al menos el doble de app.presence.refresh-interval");
    }
  }
}
