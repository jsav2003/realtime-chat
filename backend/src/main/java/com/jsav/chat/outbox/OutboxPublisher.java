package com.jsav.chat.outbox;

import com.jsav.chat.config.OutboxProperties;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lleva los eventos de la outbox a RabbitMQ (ADR 0004, ADR 0012). Cada vuelta es <b>una
 * transacción</b>: toma un lote con {@code FOR UPDATE SKIP LOCKED}, lo publica, espera las
 * confirmaciones y recién entonces marca como publicado lo confirmado.
 *
 * <p>Si el proceso muere después de publicar y antes de marcar, la transacción se pierde, los
 * eventos siguen pendientes y salen otra vez: la entrega es <b>al menos una vez</b>, y quien
 * consume deduplica (ADR 0013). Nunca al revés: marcar antes de publicar perdería eventos.
 *
 * <p>Corre en cada instancia del backend. Se apaga con {@code app.outbox.publisher.enabled=false}.
 */
@Component
@ConditionalOnProperty(
    name = "app.outbox.publisher.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  /** Con RabbitMQ caído, cada vuelta falla: se avisa a lo sumo una vez cada tanto. */
  private static final long WARN_EVERY_MILLIS = 10_000;

  private final OutboxRepository outbox;
  private final OutboxTransport transport;
  private final TransactionTemplate tx;
  private final OutboxProperties props;
  private final AtomicLong lastWarnAt = new AtomicLong();

  public OutboxPublisher(
      OutboxRepository outbox,
      OutboxTransport transport,
      TransactionTemplate tx,
      OutboxProperties props) {
    this.outbox = outbox;
    this.transport = transport;
    this.tx = tx;
    this.props = props;
  }

  /**
   * Una vuelta: publica un lote y devuelve cuántos eventos quedaron publicados. Si el lote no se
   * pudo publicar, vuelve con 0 y los eventos siguen pendientes. Público para poder manejarlo desde
   * las pruebas, sin esperar al planificador.
   */
  public int drainOnce() {
    Integer published =
        tx.execute(
            status -> {
              List<OutboxEvent> pending = outbox.lockPending(props.batchSize());
              if (pending.isEmpty()) {
                return 0;
              }
              Set<Long> confirmed = transport.publish(pending);
              outbox.markPublished(confirmed);
              return confirmed.size();
            });
    return published == null ? 0 : published;
  }

  @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
  void tick() {
    try {
      // Con un lote lleno puede haber más esperando: se sigue sin esperar al próximo tick.
      while (drainOnce() >= props.batchSize()) {
        // otra vuelta
      }
    } catch (RuntimeException e) {
      warnThrottled(e);
    }
  }

  private void warnThrottled(RuntimeException e) {
    long now = System.currentTimeMillis();
    long last = lastWarnAt.get();
    if (now - last >= WARN_EVERY_MILLIS && lastWarnAt.compareAndSet(last, now)) {
      log.warn("No se pudo publicar la outbox en esta vuelta; los eventos siguen pendientes", e);
    }
  }
}
