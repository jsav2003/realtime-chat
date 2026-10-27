package com.jsav.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jsav.chat.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompBrokerRelayMessageHandler;

/** El backend arranca con el relay y logra conectarse al plugin STOMP de RabbitMQ. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StompRelayIntegrationTest {

  @Autowired StompBrokerRelayMessageHandler relay;

  @Test
  void relayConnectsToRabbitmq() {
    await().atMost(Duration.ofSeconds(30)).until(relay::isBrokerAvailable);
    assertThat(relay.isBrokerAvailable()).isTrue();
  }
}
