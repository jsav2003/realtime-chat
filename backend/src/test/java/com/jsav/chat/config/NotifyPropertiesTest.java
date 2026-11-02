package com.jsav.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotifyPropertiesTest {

  @EnableConfigurationProperties({OutboxProperties.class, WorkerProperties.class})
  static class Config {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void bindsTheOutboxAndTheWorker() {
    runner
        .withPropertyValues(
            "app.outbox.poll-interval=PT0.2S",
            "app.outbox.batch-size=100",
            "app.outbox.confirm-timeout=PT5S",
            "app.worker.retry-delays=PT2S,PT10S,PT1M")
        .run(
            ctx -> {
              OutboxProperties outbox = ctx.getBean(OutboxProperties.class);
              assertThat(outbox.pollInterval()).isEqualTo(Duration.ofMillis(200));
              assertThat(outbox.batchSize()).isEqualTo(100);
              assertThat(outbox.confirmTimeout()).isEqualTo(Duration.ofSeconds(5));
              WorkerProperties worker = ctx.getBean(WorkerProperties.class);
              assertThat(worker.retryDelays())
                  .containsExactly(
                      Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMinutes(1));
              assertThat(worker.maxAttempts()).isEqualTo(4);
            });
  }

  @Test
  void theWaitsMustGrow() {
    assertThatThrownBy(
            () -> new WorkerProperties(List.of(Duration.ofSeconds(10), Duration.ofSeconds(2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new WorkerProperties(List.of(Duration.ofSeconds(2), Duration.ofSeconds(2))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkerProperties(List.of(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WorkerProperties(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theOutboxNeedsPositiveValues() {
    Duration one = Duration.ofSeconds(1);
    assertThatThrownBy(() -> new OutboxProperties(Duration.ZERO, 10, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(one, 0, one))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboxProperties(one, 10, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
