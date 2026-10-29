package com.jsav.chat.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.ratelimit.TokenBucket.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * El token bucket aguanta solicitudes simultáneas (DESIGN.md §8.3): 100 salen a la vez, detrás de
 * un mismo latch, contra un Redis real. Con capacidad 20 y sin recarga, pasan exactamente 20.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TokenBucketConcurrencyIntegrationTest {

  @Autowired TokenBucket bucket;

  private static String freshKey() {
    return "test:rl:" + UUID.randomUUID();
  }

  @Test
  void cien_solicitudes_simultaneas_contra_un_bucket_de_20_dejan_pasar_exactamente_20()
      throws Exception {
    String key = freshKey();
    int requests = 100;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    CountDownLatch ready = new CountDownLatch(requests);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<Decision>> futures = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  return bucket.tryAcquire(key, 20, 0);
                }));
      }
      ready.await();
      go.countDown();

      long allowed = 0;
      for (Future<Decision> f : futures) {
        if (f.get().allowed()) {
          allowed++;
        }
      }
      assertThat(allowed).isEqualTo(20);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void un_bucket_vacio_dice_cuanto_falta_y_uno_sin_recarga_dice_que_nunca() {
    String refilling = freshKey();
    for (int i = 0; i < 3; i++) {
      assertThat(bucket.tryAcquire(refilling, 3, 60).allowed()).isTrue();
    }
    // 60 por minuto = 1 por segundo: falta cerca de un segundo (y nunca más).
    Decision denied = bucket.tryAcquire(refilling, 3, 60);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterMs()).isBetween(1L, 1000L);

    String frozen = freshKey();
    assertThat(bucket.tryAcquire(frozen, 1, 0).allowed()).isTrue();
    assertThat(bucket.tryAcquire(frozen, 1, 0).retryAfterMs()).isEqualTo(-1);
  }

  @Test
  void el_bucket_se_recarga_con_el_tiempo() throws Exception {
    String key = freshKey();
    // 600 por minuto = 1 cada 100 ms.
    for (int i = 0; i < 2; i++) {
      assertThat(bucket.tryAcquire(key, 2, 600).allowed()).isTrue();
    }
    assertThat(bucket.tryAcquire(key, 2, 600).allowed()).isFalse();

    Thread.sleep(350);

    assertThat(bucket.tryAcquire(key, 2, 600).allowed()).isTrue();
  }

  @Test
  void cada_clave_tiene_su_propio_bucket() {
    String a = freshKey();
    String b = freshKey();
    assertThat(bucket.tryAcquire(a, 1, 0).allowed()).isTrue();
    assertThat(bucket.tryAcquire(a, 1, 0).allowed()).isFalse();
    assertThat(bucket.tryAcquire(b, 1, 0).allowed()).isTrue();
  }
}
