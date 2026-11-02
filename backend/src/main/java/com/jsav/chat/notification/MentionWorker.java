package com.jsav.chat.notification;

import com.jsav.chat.mention.MentionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * El worker de notificaciones (ADR 0013): lee los eventos de mención de la cola y crea una
 * notificación por cada (mensaje, persona).
 *
 * <p>Recibe <b>al menos una vez</b>: un evento puede llegar repetido (el publicador puede
 * republicar, y RabbitMQ reentrega lo que no se confirmó). Lo que lo hace seguro es que procesar un
 * evento es idempotente: {@link NotificationRepository#insertIfAbsent} no crea una segunda
 * notificación. El mensaje se confirma ({@code ack}) cuando este método vuelve, es decir, después
 * del commit: si el proceso muere antes, RabbitMQ lo entrega de nuevo.
 *
 * <p>Se apaga con {@code app.worker.enabled=false} (los backends del cluster no lo ejecutan).
 */
@Component
public class MentionWorker {

  /** Cuántas notificaciones creó este proceso. */
  static final String NOTIFIED = "mentions.notified";

  /** Cuántos eventos llegaron para una notificación que ya existía y se descartaron. */
  static final String DUPLICATES = "mentions.duplicates";

  /** Nombre del contenedor en el registro de listeners; las pruebas lo arrancan y lo paran. */
  public static final String LISTENER_ID = "mention-worker";

  private final NotificationRepository notifications;
  private final JsonMapper json;
  private final MeterRegistry meters;

  public MentionWorker(
      NotificationRepository notifications, JsonMapper json, MeterRegistry meters) {
    this.notifications = notifications;
    this.json = json;
    this.meters = meters;
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = MentionTopology.QUEUE,
      concurrency = "2",
      autoStartup = "${app.worker.enabled:true}")
  public void onMention(Message message) {
    MentionEvent event;
    try {
      event = json.readValue(message.getBody(), MentionEvent.class);
    } catch (JacksonException e) {
      // Un evento que no se puede leer no se arregla reintentándolo.
      throw new AmqpRejectAndDontRequeueException("Evento de mención ilegible", e);
    }
    boolean created =
        notifications.insertIfAbsent(event.messageId(), event.userId(), event.channelId());
    meters.counter(created ? NOTIFIED : DUPLICATES).increment();
  }
}
