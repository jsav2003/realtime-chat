package com.jsav.chat.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo habla el backend con el relay STOMP de RabbitMQ y desde qué orígenes se aceptan WebSockets
 * (DESIGN.md §6).
 */
@ConfigurationProperties(prefix = "app.realtime")
public record RealtimeProperties(
    String relayHost,
    int relayPort,
    String relayLogin,
    String relayPasscode,
    List<String> allowedOrigins) {}
