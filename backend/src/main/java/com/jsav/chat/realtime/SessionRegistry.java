package com.jsav.chat.realtime;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Los WebSockets abiertos en ESTA instancia y a qué usuario pertenece cada uno. Con el relay, las
 * suscripciones viven en RabbitMQ y el backend no puede quitar una sola (ADR 0002): para cortar a
 * alguien se cierra su socket. Cada instancia solo conoce los suyos; llegar a los de las otras es
 * trabajo del canal de control (ADR 0007).
 */
@Component
public class SessionRegistry {

  private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

  private final ConcurrentMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> owners = new ConcurrentHashMap<>();
  private final List<ConnectionListener> listeners;

  public SessionRegistry(List<ConnectionListener> listeners) {
    this.listeners = List.copyOf(listeners);
  }

  /** Un socket se abrió; todavía no se sabe de quién es (eso llega con el CONNECT). */
  void opened(WebSocketSession socket) {
    sockets.put(socket.getId(), socket);
  }

  /** El socket se cerró, por quien sea: se olvida junto con su dueño. */
  void closed(String sessionId) {
    sockets.remove(sessionId);
    Long userId = owners.remove(sessionId);
    if (userId != null) {
      for (ConnectionListener listener : listeners) {
        try {
          listener.disconnected(sessionId, userId);
        } catch (RuntimeException e) {
          log.warn("Un oyente falló al cerrarse la sesión {}", sessionId, e);
        }
      }
    }
  }

  /** El CONNECT autenticó a {@code userId} en esta sesión. */
  void bind(String sessionId, Long userId) {
    owners.put(sessionId, userId);
    for (ConnectionListener listener : listeners) {
      try {
        listener.connected(sessionId, userId);
      } catch (RuntimeException e) {
        log.warn("Un oyente falló al autenticarse la sesión {}", sessionId, e);
      }
    }
  }

  /** Las sesiones autenticadas de esta instancia y su dueño, en el momento de la llamada. */
  public Map<String, Long> boundSessions() {
    return Map.copyOf(owners);
  }

  /** Si la sesión sigue abierta y autenticada en esta instancia. */
  public boolean isBound(String sessionId) {
    return owners.containsKey(sessionId);
  }

  /** Los sockets abiertos de {@code userId} en esta instancia. */
  List<WebSocketSession> socketsOf(Long userId) {
    return owners.entrySet().stream()
        .filter(e -> e.getValue().equals(userId))
        .map(e -> sockets.get(e.getKey()))
        .filter(s -> s != null)
        .toList();
  }

  /**
   * Cierra los sockets de {@code userId} en esta instancia con 1008 (policy violation). Devuelve
   * cuántos cerró. Al cerrar el socket, Spring manda DISCONNECT al broker y RabbitMQ descarta sus
   * suscripciones; cuando reconecte, {@code SUBSCRIBE} vuelve a pasar por {@code AccessPolicy}.
   */
  public int closeSocketsOf(Long userId) {
    int closed = 0;
    for (WebSocketSession socket : socketsOf(userId)) {
      try {
        socket.close(CloseStatus.POLICY_VIOLATION.withReason("Ya no tienes acceso"));
        closed++;
      } catch (IOException e) {
        log.warn("No se pudo cerrar el socket {} del usuario {}", socket.getId(), userId, e);
      }
    }
    return closed;
  }

  int openSockets() {
    return sockets.size();
  }
}
