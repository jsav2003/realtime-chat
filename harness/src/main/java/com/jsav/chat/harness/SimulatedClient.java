package com.jsav.chat.harness;

import com.jayway.jsonpath.JsonPath;
import com.jsav.chat.harness.ApiClient.Account;
import com.jsav.chat.harness.ApiClient.Stored;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Un cliente que hace lo mismo que el frontend (DESIGN.md §3.2): se conecta a una instancia con su
 * JWT, se suscribe al canal y, cuando el servidor confirma la suscripción, pide por HTTP lo que se
 * pudo perder desde el último {@code seq} que tiene. Si recibe un seq que salta, también pide lo
 * que falta. Si se corta la conexión, reconecta (a la instancia que le indiquen o a la otra).
 */
public final class SimulatedClient implements AutoCloseable {

  private final int index;
  private final Account account;
  private final long channelId;
  private final Scenario scenario;
  private final ApiClient api;
  private final WebSocketStompClient stomp;
  private final ClientLog log = new ClientLog();

  /** Una sola tarea de resincronización a la vez por cliente; los disparadores no se pisan. */
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  private final Object resyncLock = new Object();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicInteger connects = new AtomicInteger();
  private final AtomicInteger resyncs = new AtomicInteger();
  private final AtomicLong subscribedCount = new AtomicLong();

  private volatile StompSession session;
  private volatile Instance current;

  public SimulatedClient(
      int index, Account account, long channelId, Scenario scenario, ApiClient api) {
    this.index = index;
    this.account = account;
    this.channelId = channelId;
    this.scenario = scenario;
    this.api = api;
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setDaemon(true);
    scheduler.setThreadNamePrefix("client-" + index + "-");
    scheduler.initialize();
    this.stomp = new WebSocketStompClient(new StandardWebSocketClient());
    this.stomp.setMessageConverter(new AnyToString());
    this.stomp.setTaskScheduler(scheduler);
  }

  public ClientLog log() {
    return log;
  }

  public int connects() {
    return connects.get();
  }

  public int resyncs() {
    return resyncs.get();
  }

  public Instance currentInstance() {
    return current;
  }

  /** Conecta a {@code instance}; si falla, lo intenta de nuevo con la otra, hasta conseguirlo. */
  public void connect(Instance instance) {
    Instance target = instance;
    while (!stopped.get()) {
      try {
        open(target);
        return;
      } catch (RuntimeException e) {
        target = target.other();
        pause(300);
      }
    }
  }

  /** Corta la conexión como si se cayera la red: sin avisar al servidor. */
  public void dropConnection() {
    StompSession s = session;
    session = null;
    if (s != null) {
      try {
        s.disconnect();
      } catch (RuntimeException ignored) {
        // ya estaba cerrada
      }
    }
  }

  /** Pide por HTTP todo lo posterior a lo que se tiene, reintentando si el backend no responde. */
  public void resyncBlocking() {
    synchronized (resyncLock) {
      for (int attempt = 0; attempt < 60 && !stopped.get(); attempt++) {
        try {
          for (Stored m : api.listAfter(account, channelId, log.lastSeq())) {
            log.receive(m, ClientLog.Source.RESYNC);
          }
          resyncs.incrementAndGet();
          return;
        } catch (RuntimeException e) {
          pause(500);
        }
      }
      throw new IllegalStateException("El cliente " + index + " no pudo resincronizarse");
    }
  }

  private void open(Instance instance) {
    String ws = scenario.urlOf(instance).replaceFirst("^http", "ws") + "/ws";
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + account.token());
    try {
      session =
          stomp
              .connectAsync(ws, new WebSocketHttpHeaders(), connectHeaders, new Lifecycle(instance))
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
      current = instance;
      connects.incrementAndGet();
    } catch (Exception e) {
      throw new IllegalStateException("No se pudo conectar a " + ws + ": " + e, e);
    }
  }

  /**
   * Se suscribe y, solo cuando el servidor confirma, resincroniza (ver frontend/lib/realtime.ts).
   */
  private void subscribe(StompSession s) {
    StompHeaders headers = new StompHeaders();
    headers.setDestination("/topic/channel." + channelId);
    headers.setReceipt("sub-" + index + "-" + subscribedCount.incrementAndGet());
    s.subscribe(headers, new Live()).addReceiptTask(() -> worker.execute(this::resyncQuietly));
  }

  private void resyncQuietly() {
    if (stopped.get()) {
      return;
    }
    try {
      resyncBlocking();
    } catch (RuntimeException ignored) {
      // el resync final del arnés lo vuelve a intentar con más paciencia
    }
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    stopped.set(true);
    dropConnection();
    worker.shutdownNow();
    stomp.stop();
  }

  /** Mensajes en vivo: se guardan y, si el seq salta, se pide lo que falta. */
  private final class Live implements StompFrameHandler {
    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      String json = (String) payload;
      long known = log.lastSeq();
      Stored m =
          new Stored(
              ((Number) JsonPath.read(json, "$.seq")).longValue(),
              UUID.fromString(JsonPath.read(json, "$.clientMsgId")),
              JsonPath.read(json, "$.body"));
      log.receive(m, ClientLog.Source.LIVE);
      if (m.seq() > known + 1) {
        worker.execute(SimulatedClient.this::resyncQuietly);
      }
    }
  }

  /** Ciclo de vida de la sesión STOMP: suscribirse al conectar y reconectar si se corta sola. */
  private final class Lifecycle extends StompSessionHandlerAdapter {
    private final Instance instance;

    Lifecycle(Instance instance) {
      this.instance = instance;
    }

    @Override
    public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
      subscribe(s);
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleTransportError(StompSession s, Throwable exception) {
      // Se cortó sin que el arnés lo pidiera (por ejemplo, se reinició la instancia): reconectar.
      if (stopped.get() || session != s) {
        return;
      }
      session = null;
      worker.execute(() -> connect(instance.other()));
    }
  }

  /** Pasa el cuerpo tal cual, sea cual sea el content-type. */
  private static final class AnyToString implements MessageConverter {
    @Override
    public Object fromMessage(Message<?> message, Class<?> targetClass) {
      Object payload = message.getPayload();
      return payload instanceof byte[] bytes
          ? new String(bytes, StandardCharsets.UTF_8)
          : String.valueOf(payload);
    }

    @Override
    public Message<?> toMessage(Object payload, MessageHeaders headers) {
      return MessageBuilder.createMessage(
          String.valueOf(payload).getBytes(StandardCharsets.UTF_8), headers);
    }
  }
}
