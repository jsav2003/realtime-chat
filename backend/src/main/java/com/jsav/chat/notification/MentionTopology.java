package com.jsav.chat.notification;

import com.jsav.chat.config.WorkerProperties;
import com.jsav.chat.mention.MentionEvent;
import com.jsav.chat.outbox.OutboxTopology;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Las colas de las menciones (ADR 0013):
 *
 * <ul>
 *   <li>{@value #QUEUE}: de aquí lee el worker. Recibe del exchange {@code chat.events} los eventos
 *       de tipo {@code mention}.
 *   <li>{@code mentions.wait.<ms>}: una cola por cada espera de reintento. Los mensajes esperan
 *       dentro hasta que se les acaba el TTL de la cola y entonces vuelven a {@value #QUEUE}. Es
 *       una cola por espera, y no un TTL distinto por mensaje, porque RabbitMQ solo vence lo que
 *       está al frente de la cola: con TTL distintos mezclados, uno largo bloquearía a los cortos.
 *   <li>{@value #DEAD_LETTERS}: el estacionamiento de los eventos que agotaron sus intentos. Nadie
 *       lee de aquí; se revisa a mano (UI de RabbitMQ).
 * </ul>
 *
 * <p>Las esperas y la cola de mensajes muertos se publican por el exchange por defecto, con el
 * nombre de la cola como clave, así que no necesitan enlaces. El nombre de cada espera lleva sus
 * milisegundos: si se cambia {@code app.worker.retry-delays}, nace una cola nueva en vez de chocar
 * con la anterior ({@code PRECONDITION_FAILED}, porque los argumentos de una cola no se pueden
 * cambiar).
 */
@Configuration
public class MentionTopology {

  public static final String QUEUE = "mentions";
  public static final String DEAD_LETTERS = "mentions.dlq";

  /** El nombre de la cola donde esperan los eventos que van a reintentarse tras {@code delay}. */
  public static String waitQueueName(Duration delay) {
    return QUEUE + ".wait." + delay.toMillis();
  }

  @Bean
  Queue mentionsQueue() {
    return QueueBuilder.durable(QUEUE).build();
  }

  @Bean
  Binding mentionsBinding(Queue mentionsQueue, DirectExchange eventsExchange) {
    return BindingBuilder.bind(mentionsQueue).to(eventsExchange).with(MentionEvent.TYPE);
  }

  @Bean
  Queue mentionsDeadLetterQueue() {
    return QueueBuilder.durable(DEAD_LETTERS).build();
  }

  @Bean
  Declarables mentionWaitQueues(WorkerProperties worker) {
    List<Declarable> declarables = new ArrayList<>();
    for (Duration delay : worker.retryDelays()) {
      declarables.add(
          QueueBuilder.durable(waitQueueName(delay))
              .ttl((int) delay.toMillis())
              .deadLetterExchange(OutboxTopology.EXCHANGE)
              .deadLetterRoutingKey(MentionEvent.TYPE)
              .build());
    }
    return new Declarables(declarables);
  }
}
