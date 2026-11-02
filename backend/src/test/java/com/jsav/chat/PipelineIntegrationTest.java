package com.jsav.chat;

import com.jsav.chat.config.OutboxProperties;
import com.jsav.chat.notification.MentionTopology;
import com.jsav.chat.outbox.OutboxRepository;
import com.jsav.chat.outbox.RabbitOutboxTransport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * La cañería de las menciones (outbox → RabbitMQ → worker) manejada a mano. En este contexto el
 * publicador y el worker están apagados: de lo contrario un hilo de fondo se llevaría las filas que
 * la prueba quiere ver pendientes. Cada prueba arma las piezas que necesita y las mueve a su ritmo.
 *
 * <p>Todas las subclases comparten un solo contexto (y sus contenedores), porque tienen exactamente
 * las mismas propiedades.
 */
@SpringBootTest(properties = {"app.outbox.publisher.enabled=false", "app.worker.enabled=false"})
@Import(TestcontainersConfiguration.class)
public abstract class PipelineIntegrationTest {

  @Autowired protected OutboxRepository outbox;
  @Autowired protected OutboxProperties outboxProps;
  @Autowired protected RabbitOutboxTransport realTransport;
  @Autowired protected TransactionTemplate tx;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected RabbitTemplate rabbit;
  @Autowired protected AmqpAdmin admin;
  @Autowired protected JsonMapper json;

  /** Cada prueba parte con la outbox y las colas vacías. */
  @BeforeEach
  protected void cleanPipeline() {
    jdbc.sql("delete from outbox").update();
    admin.purgeQueue(MentionTopology.QUEUE, false);
    admin.purgeQueue(MentionTopology.DEAD_LETTERS, false);
  }

  protected long pendingEvents() {
    return jdbc.sql("select count(*) from outbox where published_at is null")
        .query(Long.class)
        .single();
  }

  /** Saca todos los mensajes que hay en la cola y devuelve sus cuerpos. */
  protected List<String> drain(String queue) {
    List<String> bodies = new ArrayList<>();
    Message message;
    while ((message = rabbit.receive(queue, 300)) != null) {
      bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
    }
    return bodies;
  }

  protected long field(String eventJson, String name) {
    return json.readTree(eventJson).get(name).asLong();
  }
}
