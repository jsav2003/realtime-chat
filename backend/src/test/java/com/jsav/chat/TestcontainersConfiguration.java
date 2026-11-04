package com.jsav.chat;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres, RabbitMQ (con STOMP) y Redis reales, igual que docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  static final int STOMP_PORT = 61613;
  static final int REDIS_PORT = 6379;
  static final String BUCKET = "chat-test";

  /** Presencia, «escribiendo…» y rate limiting (hito 5). */
  @Bean
  @ServiceConnection(name = "redis")
  @SuppressWarnings("resource") // Spring cierra el contenedor al cerrar el contexto
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(REDIS_PORT);
  }

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

  /**
   * El S3 de los adjuntos (hito 7): el mismo Adobe S3Mock que docker-compose.yml, con el bucket
   * creado.
   */
  @Bean
  S3MockContainer s3Container() {
    return new S3MockContainer("5.2.3").withInitialBuckets(BUCKET);
  }

  /** Apunta el almacén al puerto mapeado: el backend y las URLs firmadas usan el mismo host. */
  @Bean
  DynamicPropertyRegistrar storageProperties(S3MockContainer s3) {
    return registry -> {
      registry.add("app.storage.endpoint", s3::getHttpEndpoint);
      registry.add("app.storage.public-endpoint", s3::getHttpEndpoint);
      registry.add("app.storage.bucket", () -> BUCKET);
    };
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
