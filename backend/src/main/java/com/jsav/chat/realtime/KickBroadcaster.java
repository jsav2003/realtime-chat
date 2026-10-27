package com.jsav.chat.realtime;

import com.jsav.chat.server.UserKicked;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Corta los WebSockets de quien acaba de ser expulsado (DESIGN.md §4). Con el relay no se puede
 * quitar una suscripción suelta, así que se cierran sus sockets: primero los de esta instancia, sin
 * depender de nadie, y luego se avisa a las demás por el canal de control (ADR 0007).
 */
@Component
public class KickBroadcaster {

  private final SessionRegistry sessions;
  private final ControlChannel control;

  public KickBroadcaster(SessionRegistry sessions, ControlChannel control) {
    this.sessions = sessions;
    this.control = control;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onUserKicked(UserKicked event) {
    sessions.closeSocketsOf(event.userId());
    control.announceKicked(event.userId());
  }
}
