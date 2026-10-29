package com.jsav.chat.presence;

import com.jsav.chat.config.PresenceProperties;
import com.jsav.chat.realtime.ConnectionListener;
import com.jsav.chat.realtime.InstanceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Escribe la presencia cuando un WebSocket se autentica y la borra cuando se cierra (ADR 0008). Si
 * Redis no responde, lo registra y sigue: la conexión no depende de la presencia, y la renovación
 * periódica la vuelve a escribir cuando Redis regrese.
 */
@Component
public class PresenceTracker implements ConnectionListener {

  private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);

  private final PresenceStore store;
  private final PresenceProperties props;
  private final InstanceId instance;

  public PresenceTracker(PresenceStore store, PresenceProperties props, InstanceId instance) {
    this.store = store;
    this.props = props;
    this.instance = instance;
  }

  /** Una conexión es única en todo el sistema: instancia y sesión. */
  String connectionId(String sessionId) {
    return instance.value() + ":" + sessionId;
  }

  @Override
  public void connected(String sessionId, Long userId) {
    try {
      store.touch(userId, connectionId(sessionId), props.ttl());
    } catch (DataAccessException e) {
      log.warn("Redis no responde: no se pudo marcar en línea al usuario {}", userId, e);
    }
  }

  @Override
  public void disconnected(String sessionId, Long userId) {
    try {
      store.remove(userId, connectionId(sessionId));
    } catch (DataAccessException e) {
      // Sin el borrado, la conexión vence sola en a lo sumo ttl.
      log.warn("Redis no responde: el usuario {} se apagará por vencimiento", userId, e);
    }
  }
}
