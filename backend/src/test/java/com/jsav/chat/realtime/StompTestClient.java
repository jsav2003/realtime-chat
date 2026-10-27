package com.jsav.chat.realtime;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Un cliente STOMP real contra {@code ws://localhost:{port}/ws}, para los tests de integración.
 * Entrega los mensajes como texto JSON y guarda los frames ERROR que mande el servidor.
 */
final class StompTestClient implements AutoCloseable {

  private static final Duration TIMEOUT = Duration.ofSeconds(15);
  private static final AtomicInteger RECEIPTS = new AtomicInteger();

  private final WebSocketStompClient client;
  private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
  private final CompletableFuture<StompSession> connected = new CompletableFuture<>();
  private final CompletableFuture<Void> closed = new CompletableFuture<>();
  private volatile StompSession session;

  private StompTestClient(int port, String token) {
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new StringMessageConverter(StandardCharsets.UTF_8));
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setDaemon(true);
    scheduler.setThreadNamePrefix("stomp-test-");
    scheduler.initialize();
    client.setTaskScheduler(scheduler);
    StompHeaders connectHeaders = new StompHeaders();
    if (token != null) {
      connectHeaders.add("Authorization", "Bearer " + token);
    }
    client.connectAsync(
        "ws://localhost:" + port + "/ws",
        new WebSocketHttpHeaders(),
        connectHeaders,
        new SessionHandler());
  }

  /** Conecta y espera el CONNECTED. Si el servidor responde ERROR, lanza con su mensaje. */
  static StompTestClient connect(int port, String token) {
    StompTestClient c = new StompTestClient(port, token);
    String error = c.awaitConnected();
    if (error != null) {
      c.close();
      throw new IllegalStateException("CONNECT rechazado: " + error);
    }
    return c;
  }

  /** El mensaje del ERROR si el CONNECT fue rechazado; {@code null} si conectó. */
  static String connectError(int port, String token) {
    try (StompTestClient c = new StompTestClient(port, token)) {
      return c.awaitConnected();
    }
  }

  private String awaitConnected() {
    try {
      connected.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return null;
    } catch (Exception e) {
      String error = pollError(Duration.ofSeconds(2));
      return error != null ? error : "sin respuesta: " + e;
    }
  }

  /** Se suscribe y espera el RECEIPT. Devuelve {@code null} si quedó suscrito, o el ERROR. */
  String subscribe(String destination) {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setReceipt("r-" + RECEIPTS.incrementAndGet());
    CompletableFuture<Boolean> receipt = new CompletableFuture<>();
    session.subscribe(headers, new Handler()).addReceiptTask(() -> receipt.complete(true));
    try {
      receipt.get(3, TimeUnit.SECONDS);
      return null;
    } catch (Exception e) {
      String error = pollError(Duration.ofSeconds(5));
      return error != null ? error : "sin receipt ni ERROR";
    }
  }

  /** Manda un SEND. El servidor debería rechazarlo: el ERROR queda en {@link #pollError}. */
  void send(String destination, String body) {
    session.send(destination, body);
  }

  /** El siguiente mensaje recibido, o {@code null} si no llega a tiempo. */
  String nextMessage(Duration wait) {
    return poll(messages, wait);
  }

  String pollError(Duration wait) {
    return poll(errors, wait);
  }

  boolean isConnected() {
    return session != null && session.isConnected();
  }

  /** Espera a que el servidor cierre la conexión. */
  boolean awaitClosed(Duration wait) {
    try {
      closed.get(wait.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void close() {
    StompSession s = session;
    if (s != null && s.isConnected()) {
      s.disconnect();
    }
    client.stop();
  }

  private static String poll(BlockingQueue<String> queue, Duration wait) {
    try {
      return queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private final class Handler implements StompFrameHandler {
    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      messages.add((String) payload);
    }
  }

  private final class SessionHandler extends StompSessionHandlerAdapter {
    @Override
    public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
      session = s;
      connected.complete(s);
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      errors.add(String.valueOf(headers.getFirst("message")));
    }

    @Override
    public void handleTransportError(StompSession s, Throwable exception) {
      closed.complete(null);
      connected.completeExceptionally(exception);
    }
  }
}
