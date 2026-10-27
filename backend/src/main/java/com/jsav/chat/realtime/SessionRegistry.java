package com.jsav.chat.realtime;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Los WebSockets abiertos en ESTA instancia y a qué usuario pertenece cada uno. Con el relay, las
 * suscripciones viven en RabbitMQ y el backend no puede quitar una sola (ADR 0002): para cortar a
 * alguien se cierra su socket. Cada instancia solo conoce los suyos; llegar a los de las otras es
 * trabajo del canal de control (ADR 0007).
 */
@Component
public class SessionRegistry {

  private final ConcurrentMap<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> owners = new ConcurrentHashMap<>();

  /** Un socket se abrió; todavía no se sabe de quién es (eso llega con el CONNECT). */
  void opened(WebSocketSession socket) {
    sockets.put(socket.getId(), socket);
  }

  /** El socket se cerró, por quien sea: se olvida junto con su dueño. */
  void closed(String sessionId) {
    sockets.remove(sessionId);
    owners.remove(sessionId);
  }

  /** El CONNECT autenticó a {@code userId} en esta sesión. */
  void bind(String sessionId, Long userId) {
    owners.put(sessionId, userId);
  }

  /** Los sockets abiertos de {@code userId} en esta instancia. */
  List<WebSocketSession> socketsOf(Long userId) {
    return owners.entrySet().stream()
        .filter(e -> e.getValue().equals(userId))
        .map(e -> sockets.get(e.getKey()))
        .filter(s -> s != null)
        .toList();
  }

  int openSockets() {
    return sockets.size();
  }
}
