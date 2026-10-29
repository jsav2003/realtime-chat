package com.jsav.chat.typing;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.realtime.ChannelTopic;
import com.jsav.chat.user.UserRepository;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * «Escribiendo…» (DESIGN.md §5, ADR 0010). Es un aviso efímero: no se guarda, no lleva {@code seq}
 * y queda fuera de las garantías de entrega. Entra por HTTP, como todo lo que se escribe (ADR
 * 0006), y sale por {@code /topic/channel.{id}.typing}.
 */
@Service
public class TypingService {

  /** Dentro de esta ventana, más avisos del mismo usuario en el mismo canal se descartan. */
  static final Duration WINDOW = Duration.ofSeconds(2);

  private static final Logger log = LoggerFactory.getLogger(TypingService.class);

  private final AccessPolicy policy;
  private final StringRedisTemplate redis;
  private final UserRepository users;
  private final SimpMessagingTemplate template;

  public TypingService(
      AccessPolicy policy,
      StringRedisTemplate redis,
      UserRepository users,
      SimpMessagingTemplate template) {
    this.policy = policy;
    this.redis = redis;
    this.users = users;
    this.template = template;
  }

  /**
   * Avisa que {@code userId} está escribiendo en {@code channelId}. Escribe quien puede escribir
   * (WRITE): un extraño recibe 404, igual que al enviar un mensaje.
   */
  public void typing(Long userId, Long channelId) {
    policy.requireChannel(userId, channelId, ChannelAction.WRITE);
    if (!firstInWindow(userId, channelId)) {
      return;
    }
    String name = users.findById(userId).map(u -> u.getDisplayName()).orElse("Alguien");
    template.convertAndSend(ChannelTopic.typingOf(channelId), new TypingEvent(userId, name));
  }

  /**
   * Deja pasar un aviso por usuario y canal cada {@link #WINDOW}. Si Redis no responde, deja pasar
   * todos: es solo un freno, y «escribiendo…» no justifica fallar.
   */
  private boolean firstInWindow(Long userId, Long channelId) {
    try {
      return Boolean.TRUE.equals(
          redis.opsForValue().setIfAbsent("typing:" + channelId + ":" + userId, "1", WINDOW));
    } catch (DataAccessException e) {
      log.warn("Redis no responde: «escribiendo…» sale sin freno", e);
      return true;
    }
  }
}
