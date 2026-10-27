package com.jsav.chat.realtime;

import com.jsav.chat.config.RealtimeProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket con STOMP y relay a RabbitMQ (ADR 0002). Con el relay, cualquier instancia publica en
 * RabbitMQ y RabbitMQ entrega a los suscriptores de todas; el broker simple en memoria solo
 * serviría con una instancia.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  /** Prefijo de los destinos a los que se suscriben los clientes: {@code /topic/channel.{id}}. */
  public static final String TOPIC_PREFIX = "/topic";

  private final RealtimeProperties props;
  private final StompSecurityInterceptor security;

  public WebSocketConfig(RealtimeProperties props, StompSecurityInterceptor security) {
    this.props = props;
    this.security = security;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(props.allowedOrigins().toArray(String[]::new));
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableStompBrokerRelay(TOPIC_PREFIX)
        .setRelayHost(props.relayHost())
        .setRelayPort(props.relayPort())
        .setClientLogin(props.relayLogin())
        .setClientPasscode(props.relayPasscode())
        .setSystemLogin(props.relayLogin())
        .setSystemPasscode(props.relayPasscode());
  }
}
