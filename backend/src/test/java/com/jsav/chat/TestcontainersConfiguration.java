package com.jsav.chat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/** Redis se agrega cuando llegue su hito (presencia y rate limiting). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final int STOMP_PORT = 61613;

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
  }

  /** RabbitMQ con el plugin STOMP, igual que el de docker-compose.yml. */
  @Bean
  @ServiceConnection
  @SuppressWarnings("resource") // Spring cierra el contenedor al cerrar el contexto
  RabbitMQContainer rabbitContainer() {
    return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4"))
        .withCopyToContainer(
            Transferable.of("[rabbitmq_stomp].", 0644), "/etc/rabbitmq/enabled_plugins")
        .withExposedPorts(5672, STOMP_PORT);
  }

  /** El relay STOMP no entra por @ServiceConnection: se apunta a mano al puerto mapeado. */
  @Bean
  DynamicPropertyRegistrar relayProperties(RabbitMQContainer rabbit) {
    return registry -> {
      registry.add("app.realtime.relay-host", rabbit::getHost);
      registry.add("app.realtime.relay-port", () -> rabbit.getMappedPort(STOMP_PORT));
      registry.add("app.realtime.relay-login", rabbit::getAdminUsername);
      registry.add("app.realtime.relay-passcode", rabbit::getAdminPassword);
    };
  }
}
