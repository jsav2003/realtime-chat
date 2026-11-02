package com.jsav.chat.outbox;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El exchange al que el publicador lleva los eventos de la outbox. La clave de ruteo es el {@code
 * event_type}: cada tipo de evento tiene su cola, enlazada con esa clave (para las menciones, ver
 * {@code MentionTopology}).
 *
 * <p>Lo declaran todos los procesos, no solo el que consume: si el exchange o la cola no existieran
 * cuando un backend publica, RabbitMQ descartaría el evento.
 */
@Configuration
public class OutboxTopology {

  public static final String EXCHANGE = "chat.events";

  @Bean
  DirectExchange eventsExchange() {
    return new DirectExchange(EXCHANGE);
  }
}
