package com.jsav.chat.readstate;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que cada persona lleva leído de cada canal (ADR 0015). El envío de mensajes no pasa por aquí:
 * el cursor lo mueve el cliente cuando muestra los mensajes, nunca dentro de la transacción que
 * tiene bloqueada la fila del canal.
 */
@Service
public class ReadStateService {

  private final ReadStateRepository readState;
  private final AccessPolicy policy;

  public ReadStateService(ReadStateRepository readState, AccessPolicy policy) {
    this.readState = readState;
    this.policy = policy;
  }

  /**
   * Marca el canal como leído hasta {@code seq}. Solo quien puede leer el canal: para el resto es
   * 404, igual que pedir sus mensajes.
   */
  @Transactional
  public void markRead(Long userId, Long channelId, long seq) {
    policy.requireChannel(userId, channelId, ChannelAction.READ);
    readState.advance(userId, channelId, seq);
  }
}
