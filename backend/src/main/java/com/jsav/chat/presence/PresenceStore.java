package com.jsav.chat.presence;

import com.jsav.chat.common.RedisScripts;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Quién está en línea, guardado en Redis (ADR 0008). Cada usuario tiene un ZSET {@code
 * presence:{userId}} con un miembro por conexión viva, de cualquier pestaña o instancia, y como
 * puntaje la hora en que esa conexión vence. Un usuario está en línea si le queda algún miembro sin
 * vencer.
 *
 * <p>Si la pestaña o la instancia mueren sin avisar, nadie renueva su miembro y vence solo. No
 * decide qué hacer si Redis no responde: la excepción sube a quien lo usa.
 */
@Component
public class PresenceStore {

  private static final DefaultRedisScript<Long> TOUCH = RedisScripts.ofLong("presence/touch.lua");
  private static final DefaultRedisScript<List<Long>> ONLINE =
      RedisScripts.listOfLongs("presence/online.lua");

  private final StringRedisTemplate redis;

  public PresenceStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  static String key(Long userId) {
    return "presence:" + userId;
  }

  /** La conexión sigue viva: vale como en línea durante {@code ttl} más. */
  public void touch(Long userId, String connectionId, Duration ttl) {
    redis.execute(TOUCH, List.of(key(userId)), connectionId, String.valueOf(ttl.toMillis()));
  }

  /** La conexión se cerró limpio: deja de contar en el acto, sin esperar al vencimiento. */
  public void remove(Long userId, String connectionId) {
    redis.opsForZSet().remove(key(userId), connectionId);
  }

  /** Cuáles de {@code userIds} tienen al menos una conexión viva. */
  public Set<Long> online(Collection<Long> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    List<Long> ids = List.copyOf(userIds);
    List<Long> flags = redis.execute(ONLINE, ids.stream().map(PresenceStore::key).toList());
    if (flags == null || flags.size() != ids.size()) {
      throw new IllegalStateException("Respuesta inesperada del script de presencia");
    }
    Set<Long> online = new HashSet<>();
    for (int i = 0; i < ids.size(); i++) {
      if (flags.get(i) == 1L) {
        online.add(ids.get(i));
      }
    }
    return online;
  }
}
