package com.jsav.chat.realtime;

/**
 * Se entera de los WebSockets que se autentican y se cierran en esta instancia. {@link
 * SessionRegistry} avisa a todos los que haya; un fallo de uno no afecta a la conexión ni a los
 * demás.
 */
public interface ConnectionListener {

  /** El {@code CONNECT} autenticó a {@code userId} en la sesión {@code sessionId}. */
  void connected(String sessionId, Long userId);

  /** Se cerró el socket de una sesión que había autenticado a {@code userId}. */
  void disconnected(String sessionId, Long userId);
}
