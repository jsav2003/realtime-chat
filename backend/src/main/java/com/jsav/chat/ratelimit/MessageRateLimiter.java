package com.jsav.chat.ratelimit;

import com.jsav.chat.common.TooManyRequestsException;
import com.jsav.chat.config.RateLimitProperties;
import com.jsav.chat.ratelimit.TokenBucket.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Cobra un token por cada mensaje nuevo de un usuario (ADR 0009).
 *
 * <p>Si Redis no responde, <b>deja pasar</b> y lo registra: Redis puede perderse sin perder
 * mensajes (DESIGN.md §6), y un freno que se cae no debe impedir escribir a nadie.
 */
@Component
public class MessageRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(MessageRateLimiter.class);

  private final TokenBucket bucket;
  private final RateLimitProperties props;

  public MessageRateLimiter(TokenBucket bucket, RateLimitProperties props) {
    this.bucket = bucket;
    this.props = props;
  }

  /** Lanza {@link TooManyRequestsException} si {@code userId} ya agotó su cupo. */
  public void acquire(Long userId) {
    Decision decision;
    try {
      decision = bucket.tryAcquire("rl:msg:" + userId, props.capacity(), props.refillPerMinute());
    } catch (DataAccessException e) {
      log.warn("Redis no responde: el mensaje del usuario {} pasa sin límite", userId, e);
      return;
    }
    if (!decision.allowed()) {
      long seconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);
      throw new TooManyRequestsException(
          "Estás enviando muy rápido. Intenta de nuevo en " + seconds + " s", seconds);
    }
  }
}
