package com.jsav.chat.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Canal de control entre instancias (ADR 0007). Un usuario puede tener su socket en cualquiera de
 * las instancias, y quien lo expulsa llega a una sola. La instancia que atiende la expulsión
 * publica aquí, en un exchange fanout, y cada instancia, con su propia cola, cierra los sockets que
 * tenga de ese usuario.
 *
 * <p>Si RabbitMQ no está, tampoco hay entrega en vivo ni sockets que cortar por esta vía: los
 * clientes se resincronizan por {@code seq} al volver, y {@code SUBSCRIBE} sigue pasando por {@code
 * AccessPolicy}.
 */
@Component
public class ControlChannel {

  static final String EXCHANGE = "chat.control";
  private static final String KICKED = "kicked:";

  private static final Logger log = LoggerFactory.getLogger(ControlChannel.class);

  private final RabbitTemplate rabbit;
  private final SessionRegistry sessions;

  public ControlChannel(RabbitTemplate rabbit, SessionRegistry sessions) {
    this.rabbit = rabbit;
    this.sessions = sessions;
  }

  /** Avisa a todas las instancias (incluida esta) que corten los sockets de {@code userId}. */
  public void announceKicked(Long userId) {
    try {
      rabbit.convertAndSend(EXCHANGE, "", KICKED + userId);
    } catch (AmqpException e) {
      log.warn("No se pudo avisar la expulsión del usuario {} a las demás instancias", userId, e);
    }
  }

  @RabbitListener(queues = "#{controlQueue.name}")
  void onControl(String body) {
    if (body.startsWith(KICKED)) {
      Long userId = Long.valueOf(body.substring(KICKED.length()));
      int closed = sessions.closeSocketsOf(userId);
      log.info("Expulsión del usuario {}: {} socket(s) cerrados en esta instancia", userId, closed);
    }
  }
}
