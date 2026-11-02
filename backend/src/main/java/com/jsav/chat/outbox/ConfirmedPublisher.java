package com.jsav.chat.outbox;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica en RabbitMQ y cuenta un mensaje como publicado solo si el broker lo <b>confirmó</b> y lo
 * <b>ruteó</b> a alguna cola (ADR 0012). Lo usan el publicador de la outbox y el worker (para pasar
 * un evento a una cola de espera sin perderlo):
 *
 * <ul>
 *   <li>Sin confirmación (publisher confirms), un {@code send} que no lanza no prueba nada: el
 *       mensaje pudo perderse en el camino.
 *   <li>Con {@code mandatory}, un mensaje que ninguna cola recibe vuelve devuelto en vez de
 *       descartarse en silencio. Sin esto, un exchange sin cola enlazada confirmaría y se tragaría
 *       el evento.
 * </ul>
 *
 * <p>Usa su propio {@code RabbitTemplate}, que no es un bean: un bean de ese tipo apagaría el que
 * Spring Boot configura y que usa el canal de control entre instancias.
 */
@Component
public class ConfirmedPublisher {

  /** Un mensaje por publicar y la clave de ruteo con la que sale. */
  public record Outgoing(String routingKey, Message message) {}

  private static final Logger log = LoggerFactory.getLogger(ConfirmedPublisher.class);

  private final RabbitTemplate template;

  public ConfirmedPublisher(ConnectionFactory connectionFactory) {
    this.template = new RabbitTemplate(connectionFactory);
    this.template.setMandatory(true);
    this.template.setReturnsCallback(
        returned ->
            log.warn(
                "RabbitMQ devolvió un mensaje sin cola que lo reciba (exchange «{}», clave «{}»): {}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText()));
    this.template.afterPropertiesSet();
  }

  /**
   * Publica los mensajes en {@code exchange} y espera hasta {@code timeout} (en total) a que el
   * broker confirme. Devuelve los índices, dentro de {@code outgoing}, de los que quedaron
   * confirmados y ruteados. Un broker caído no es una excepción: simplemente no confirma nada.
   */
  public Set<Integer> publish(String exchange, List<Outgoing> outgoing, Duration timeout) {
    Map<Integer, CorrelationData> inFlight = new LinkedHashMap<>();
    try {
      for (int i = 0; i < outgoing.size(); i++) {
        CorrelationData correlation = new CorrelationData(Integer.toString(i));
        Outgoing next = outgoing.get(i);
        template.send(exchange, next.routingKey(), next.message(), correlation);
        inFlight.put(i, correlation);
      }
    } catch (AmqpException e) {
      // Lo que alcanzó a salir antes del fallo se espera igual abajo; el resto no se confirma.
      log.warn("No se pudo publicar en RabbitMQ: {}", e.getMessage());
    }
    return awaitConfirms(inFlight, timeout);
  }

  /** Como {@link #publish} con un solo mensaje: {@code true} si el broker lo confirmó y ruteó. */
  public boolean publishOne(String exchange, String routingKey, Message message, Duration timeout) {
    return !publish(exchange, List.of(new Outgoing(routingKey, message)), timeout).isEmpty();
  }

  private Set<Integer> awaitConfirms(Map<Integer, CorrelationData> inFlight, Duration timeout) {
    Set<Integer> confirmed = new HashSet<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    for (Map.Entry<Integer, CorrelationData> entry : inFlight.entrySet()) {
      CorrelationData correlation = entry.getValue();
      try {
        long left = Math.max(0, deadline - System.nanoTime());
        CorrelationData.Confirm confirm = correlation.getFuture().get(left, TimeUnit.NANOSECONDS);
        if (confirm.ack() && correlation.getReturned() == null) {
          confirmed.add(entry.getKey());
        } else {
          log.warn(
              "RabbitMQ no aceptó el mensaje {} (ack={}, devuelto={}, motivo={})",
              entry.getKey(),
              confirm.ack(),
              correlation.getReturned() != null,
              confirm.reason());
        }
      } catch (TimeoutException e) {
        log.warn("RabbitMQ no confirmó el mensaje {} a tiempo", entry.getKey());
      } catch (ExecutionException e) {
        log.warn("Falló la confirmación del mensaje {}", entry.getKey(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return confirmed;
  }
}
