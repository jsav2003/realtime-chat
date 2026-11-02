package com.jsav.chat;

import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.config.OutboxProperties;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService;
import com.jsav.chat.notification.MentionTopology;
import com.jsav.chat.notification.MentionWorker;
import com.jsav.chat.outbox.OutboxRepository;
import com.jsav.chat.outbox.RabbitOutboxTransport;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * La cañería de las menciones (outbox → RabbitMQ → worker) manejada a mano. En este contexto el
 * publicador y el worker están apagados: de lo contrario un hilo de fondo se llevaría las filas que
 * la prueba quiere ver pendientes. Cada prueba arma las piezas que necesita, las mueve a su ritmo
 * y, si hace falta, arranca el worker con {@link #startWorker()}.
 *
 * <p>Todas las subclases comparten un solo contexto (y sus contenedores), porque tienen exactamente
 * las mismas propiedades. {@link WorkerCrashes} permite hacer «morir» al worker en puntos exactos.
 */
@SpringBootTest(properties = {"app.outbox.publisher.enabled=false", "app.worker.enabled=false"})
@Import({TestcontainersConfiguration.class, WorkerCrashTestConfig.class})
public abstract class PipelineIntegrationTest {

  private static final AtomicInteger WORLDS = new AtomicInteger();

  /** Un servidor con un canal público, su dueño y {@code members} miembros más. */
  protected record World(User owner, List<User> members, Long serverId, Long channelId) {

    /** El texto que menciona a esos miembros, por ejemplo {@code "@m3_1 @m3_2"}. */
    public String mentioning(User... who) {
      List<String> handles = new ArrayList<>();
      for (User u : who) {
        handles.add("@" + u.getUsername());
      }
      return String.join(" ", handles);
    }
  }

  @Autowired protected OutboxRepository outbox;
  @Autowired protected OutboxProperties outboxProps;
  @Autowired protected RabbitOutboxTransport realTransport;
  @Autowired protected TransactionTemplate tx;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected RabbitTemplate rabbit;
  @Autowired protected AmqpAdmin admin;
  @Autowired protected JsonMapper json;
  @Autowired protected MeterRegistry meters;
  @Autowired protected WorkerCrashes crashes;
  @Autowired protected RabbitListenerEndpointRegistry listeners;
  @Autowired protected MessageService messages;
  @Autowired protected UserRepository users;
  @Autowired protected ServerMemberRepository serverMembers;
  @Autowired protected ServerService serverService;
  @Autowired protected ChannelService channelService;

  /** Cada prueba parte con las tablas y las colas vacías y sin caídas armadas. */
  @BeforeEach
  protected void cleanPipeline() {
    crashes.disarm();
    jdbc.sql("delete from notifications").update();
    jdbc.sql("delete from outbox").update();
    admin.purgeQueue(MentionTopology.QUEUE, false);
    admin.purgeQueue(MentionTopology.DEAD_LETTERS, false);
  }

  /** Al terminar cada prueba el worker queda parado y sin caídas armadas. */
  @AfterEach
  protected void stopWorker() {
    stopWorkerContainer();
    crashes.disarm();
  }

  /** Para el worker sin tocar las caídas armadas. */
  protected void stopWorkerContainer() {
    var container = listeners.getListenerContainer(MentionWorker.LISTENER_ID);
    if (container != null && container.isRunning()) {
      container.stop();
    }
  }

  protected void startWorker() {
    var container = listeners.getListenerContainer(MentionWorker.LISTENER_ID);
    if (!container.isRunning()) {
      container.start();
    }
  }

  // ---- el mundo ----

  protected World newWorld(int members) {
    int run = WORLDS.incrementAndGet();
    User owner =
        users.save(new User("pw%d-o@x.test".formatted(run), "x", "Dueño", "o%d_0".formatted(run)));
    Long serverId = serverService.create(owner.getId(), new CreateServerRequest("Pipe" + run)).id();
    List<User> others = new ArrayList<>();
    for (int i = 1; i <= members; i++) {
      User u =
          users.save(
              new User(
                  "pw%d-%d@x.test".formatted(run, i),
                  "x",
                  "Miembro " + i,
                  "m%d_%d".formatted(run, i)));
      serverMembers.save(new ServerMember(serverId, u.getId(), Role.MEMBER));
      others.add(u);
    }
    Long channelId = channelService.list(owner.getId(), serverId).get(0).id();
    return new World(owner, List.copyOf(others), serverId, channelId);
  }

  /** Envía un mensaje nuevo por el camino real y devuelve su id. */
  protected long send(User from, Long channelId, String body) {
    return messages
        .send(from.getId(), channelId, new SendMessageRequest(UUID.randomUUID(), body))
        .message()
        .id();
  }

  // ---- lo que hay en las tablas y en las colas ----

  protected long pendingEvents() {
    return jdbc.sql("select count(*) from outbox where published_at is null")
        .query(Long.class)
        .single();
  }

  protected long notificationCount() {
    return jdbc.sql("select count(*) from notifications").query(Long.class).single();
  }

  /** A quién se notificó por ese mensaje, en orden. */
  protected List<Long> notifiedFor(long messageId) {
    return jdbc.sql("select user_id from notifications where message_id = :id order by user_id")
        .param("id", messageId)
        .query(Long.class)
        .list();
  }

  /** Cuánto vale un contador del worker. */
  protected double counter(String name) {
    return meters.counter(name).count();
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
