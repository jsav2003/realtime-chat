package com.jsav.chat.realtime;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exchange fanout {@code chat.control} y la cola propia de esta instancia (ADR 0007). */
@Configuration
public class ControlConfig {

  @Bean
  FanoutExchange controlExchange() {
    return new FanoutExchange(ControlChannel.EXCHANGE);
  }

  /** Cola propia de esta instancia: exclusiva, con nombre único y se borra al apagarse. */
  @Bean
  Queue controlQueue() {
    return new AnonymousQueue();
  }

  @Bean
  Binding controlBinding(FanoutExchange controlExchange, Queue controlQueue) {
    return BindingBuilder.bind(controlQueue).to(controlExchange);
  }
}
