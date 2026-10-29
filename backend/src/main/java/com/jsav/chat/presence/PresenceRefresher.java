package com.jsav.chat.presence;

import com.jsav.chat.config.PresenceProperties;
import com.jsav.chat.realtime.SessionRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El latido de la presencia (ADR 0008): cada {@code refreshInterval}, esta instancia renueva en
 * Redis las conexiones que tiene abiertas. Si la instancia muere, nadie renueva las suyas y vencen
 * solas; así un usuario no queda en línea para siempre.
 */
@Component
public class PresenceRefresher {

  private static final Logger log = LoggerFactory.getLogger(PresenceRefresher.class);

  private final SessionRegistry sessions;
  private final PresenceStore store;
  private final PresenceTracker tracker;
  private final PresenceProperties props;

  public PresenceRefresher(
      SessionRegistry sessions,
      PresenceStore store,
      PresenceTracker tracker,
      PresenceProperties props) {
    this.sessions = sessions;
    this.store = store;
    this.tracker = tracker;
    this.props = props;
  }

  @Scheduled(fixedRateString = "${app.presence.refresh-interval}")
  public void refresh() {
    for (Map.Entry<String, Long> e : sessions.boundSessions().entrySet()) {
      String sessionId = e.getKey();
      Long userId = e.getValue();
      String connectionId = tracker.connectionId(sessionId);
      try {
        store.touch(userId, connectionId, props.ttl());
        // El socket pudo cerrarse mientras se renovaba, y su borrado haber corrido antes de este
        // touch: sin esta comprobación la conexión reaparecería hasta vencer.
        if (!sessions.isBound(sessionId)) {
          store.remove(userId, connectionId);
        }
      } catch (DataAccessException ex) {
        log.warn("Redis no responde: no se renueva la presencia en esta vuelta", ex);
        return;
      }
    }
  }
}
