package com.jsav.chat.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jsav.chat.common.TooManyRequestsException;
import com.jsav.chat.config.RateLimitProperties;
import com.jsav.chat.ratelimit.TokenBucket.Decision;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class MessageRateLimiterTest {

  private final TokenBucket bucket = mock(TokenBucket.class);
  private final MessageRateLimiter limiter =
      new MessageRateLimiter(bucket, new RateLimitProperties(20, 30));

  @Test
  void permitido_no_hace_nada() {
    when(bucket.tryAcquire(eq("rl:msg:7"), eq(20), eq(30.0))).thenReturn(new Decision(true, 0));

    assertThatCode(() -> limiter.acquire(7L)).doesNotThrowAnyException();
  }

  @Test
  void denegado_redondea_hacia_arriba_los_segundos_de_espera() {
    when(bucket.tryAcquire(anyString(), anyInt(), anyDouble()))
        .thenReturn(new Decision(false, 1500));

    assertThatThrownBy(() -> limiter.acquire(7L))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(2));
  }

  @Test
  void espera_minima_de_un_segundo() {
    when(bucket.tryAcquire(anyString(), anyInt(), anyDouble())).thenReturn(new Decision(false, 3));

    assertThatThrownBy(() -> limiter.acquire(7L))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
  }

  @Test
  void si_redis_no_responde_el_mensaje_pasa() {
    when(bucket.tryAcquire(anyString(), anyInt(), anyDouble()))
        .thenThrow(new RedisConnectionFailureException("sin conexión"));

    assertThatCode(() -> limiter.acquire(7L)).doesNotThrowAnyException();
  }

  @Test
  void una_configuracion_sin_recarga_no_arranca() {
    assertThatThrownBy(() -> new RateLimitProperties(20, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateLimitProperties(0, 30))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
