package com.jsav.chat.notification;

import com.jsav.chat.config.WorkerProperties;
import com.jsav.chat.mention.MentionEvent;
import com.jsav.chat.outbox.ConfirmedPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * El worker de notificaciones (ADR 0013): lee los eventos de mención de la cola y crea una
 * notificación por cada (mensaje, persona).
 *
 * <p><b>Al menos una vez.</b> Un evento puede llegar repetido (el publicador puede republicar y
 * RabbitMQ reentrega lo que no se confirmó). Lo que lo hace seguro es que procesarlo es
 * idempotente: {@link NotificationRepository#insertIfAbsent} no crea una segunda notificación. El
 * mensaje se confirma ({@code ack}) cuando este método vuelve, es decir, después del commit: si el
 * proceso muere antes, RabbitMQ lo entrega de nuevo.
 *
 * <p><b>Cuando falla.</b> Un fallo transitorio (la base no responde) no se reintenta aquí mismo,
 * porque dormir dentro del hilo no sobrevive a la muerte del worker. El evento se pasa a una cola
 * de espera ({@code mentions.wait.<ms>}) y vuelve solo cuando se le acaba el TTL de esa cola; cada
 * intento fallido lo manda a una espera más larga. Tras el último intento, o si el error no tiene
 * arreglo (el evento no se puede leer, o el mensaje o la persona ya no existen), va a la cola de
 * mensajes muertos con el motivo.
 *
 * <p>Pasar el evento a otra cola y confirmar el original no es atómico: si el proceso muere en
 * medio, el evento queda dos veces. Lo absorbe la misma deduplicación. Lo que no puede pasar es
 * confirmar el original sin haber dejado el evento a salvo: si RabbitMQ no confirma el traspaso, se
 * lanza una excepción y el mensaje original vuelve a la cola.
 *
 * <p>Se apaga con {@code app.worker.enabled=false} (los backends del cluster no lo ejecutan).
 */
@Component
public class MentionWorker {

  /** Cuántas notificaciones creó este proceso. */
  static final String NOTIFIED = "mentions.notified";

  /** Cuántos eventos llegaron para una notificación que ya existía y se descartaron. */
  static final String DUPLICATES = "mentions.duplicates";

  /** Cuántos eventos pasaron a una cola de espera para reintentarse. */
  static final String RETRIED = "mentions.retried";

  /** Cuántos eventos terminaron en la cola de mensajes muertos. */
  static final String DEAD = "mentions.dead";

  /** Nombre del contenedor en el registro de listeners; las pruebas lo arrancan y lo paran. */
  public static final String LISTENER_ID = "mention-worker";

  /** Número del intento que está en curso, empezando en 1. Ausente = primer intento. */
  static final String ATTEMPT_HEADER = "x-attempt";

  /** Por qué falló el último intento; en la cola de mensajes muertos, por qué terminó ahí. */
  static final String ERROR_HEADER = "x-error";

  static final String FAILED_AT_HEADER = "x-failed-at";

  private static final Logger log = LoggerFactory.getLogger(MentionWorker.class);

  private static final int MAX_ERROR_LENGTH = 300;

  private final NotificationRepository notifications;
  private final ConfirmedPublisher publisher;
  private final WorkerProperties props;
  private final JsonMapper json;
  private final MeterRegistry meters;

  public MentionWorker(
      NotificationRepository notifications,
      ConfirmedPublisher publisher,
      WorkerProperties props,
      JsonMapper json,
      MeterRegistry meters) {
    this.notifications = notifications;
    this.publisher = publisher;
    this.props = props;
    this.json = json;
    this.meters = meters;
  }

  @RabbitListener(
      id = LISTENER_ID,
      queues = MentionTopology.QUEUE,
      concurrency = "2",
      autoStartup = "${app.worker.enabled:true}")
  public void onMention(Message message) {
    int attempt = attemptOf(message);
    MentionEvent event;
    try {
      event = json.readValue(message.getBody(), MentionEvent.class);
    } catch (JacksonException e) {
      // Un evento que no se puede leer no se arregla reintentándolo.
      parkForGood(message, attempt, e);
      return;
    }
    try {
      boolean created =
          notifications.insertIfAbsent(event.messageId(), event.userId(), event.channelId());
      meters.counter(created ? NOTIFIED : DUPLICATES).increment();
    } catch (DataIntegrityViolationException e) {
      // El mensaje, el canal o la persona ya no existen: reintentar no los trae de vuelta.
      parkForGood(message, attempt, e);
    } catch (RuntimeException e) {
      retryLater(message, attempt, e);
    }
  }

  private void retryLater(Message message, int attempt, RuntimeException cause) {
    if (attempt >= props.maxAttempts()) {
      parkForGood(message, attempt, cause);
      return;
    }
    Duration wait = props.retryDelays().get(attempt - 1);
    log.warn(
        "Falló el intento {} de {} de una mención; se reintenta en {} ms",
        attempt,
        props.maxAttempts(),
        wait.toMillis(),
        cause);
    handOff(MentionTopology.waitQueueName(wait), message, attempt + 1, cause);
    meters.counter(RETRIED).increment();
  }

  private void parkForGood(Message message, int attempt, Exception cause) {
    log.error(
        "Una mención terminó en la cola de mensajes muertos tras {} intento(s)", attempt, cause);
    handOff(MentionTopology.DEAD_LETTERS, message, attempt, cause);
    meters.counter(DEAD).increment();
  }

  /**
   * Deja una copia del evento en otra cola y espera la confirmación de RabbitMQ. Solo si la tiene
   * este método vuelve y el original se confirma; si no, lanza, y el original sigue en su cola para
   * entregarse de nuevo. Se publica por el exchange por defecto, con el nombre de la cola como
   * clave.
   */
  private void handOff(String queue, Message original, int attempt, Exception cause) {
    Message copy =
        MessageBuilder.withBody(original.getBody())
            .setContentType(original.getMessageProperties().getContentType())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .setMessageId(original.getMessageProperties().getMessageId())
            .setHeader(ATTEMPT_HEADER, attempt)
            .setHeader(ERROR_HEADER, describe(cause))
            .setHeader(FAILED_AT_HEADER, Instant.now().toString())
            .build();
    if (!publisher.publishOne("", queue, copy, props.confirmTimeout())) {
      throw new IllegalStateException(
          "RabbitMQ no confirmó el traspaso del evento a «"
              + queue
              + "»; queda sin confirmar para que se entregue de nuevo");
    }
  }

  private static int attemptOf(Message message) {
    Object header = message.getMessageProperties().getHeader(ATTEMPT_HEADER);
    return header instanceof Number n && n.intValue() >= 1 ? n.intValue() : 1;
  }

  private static String describe(Exception cause) {
    String text = cause.getClass().getSimpleName() + ": " + cause.getMessage();
    return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
  }
}
