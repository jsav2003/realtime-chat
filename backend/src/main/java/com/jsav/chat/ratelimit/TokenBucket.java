package com.jsav.chat.ratelimit;

import com.jsav.chat.common.RedisScripts;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket en Redis. Todo el trabajo (recargar, decidir, descontar) ocurre en un script Lua,
 * que Redis ejecuta de forma atómica: la versión de leer el contador y escribirlo después, en dos
 * pasos, deja pasar de más bajo concurrencia (DESIGN.md §5, ADR 0009).
 *
 * <p>No decide qué hacer si Redis no responde: la excepción sube a quien lo usa.
 */
@Component
public class TokenBucket {

  /**
   * {@code retryAfterMs} es cuánto falta para poder pagar el costo, o -1 si el bucket no recarga.
   */
  public record Decision(boolean allowed, long retryAfterMs) {}

  private static final DefaultRedisScript<List<Long>> SCRIPT =
      RedisScripts.listOfLongs("ratelimit/token_bucket.lua");

  private final StringRedisTemplate redis;

  public TokenBucket(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Intenta gastar un token del bucket {@code key}, que se crea lleno la primera vez. */
  public Decision tryAcquire(String key, int capacity, double refillPerMinute) {
    List<Long> result =
        redis.execute(
            SCRIPT, List.of(key), String.valueOf(capacity), String.valueOf(refillPerMinute), "1");
    if (result == null || result.size() != 2) {
      throw new IllegalStateException("Respuesta inesperada del script del token bucket");
    }
    return new Decision(result.get(0) == 1L, result.get(1));
  }
}
