package com.jsav.chat.readstate;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.channel.Channel;
import com.jsav.chat.readstate.ReadStateDtos.UnreadList;
import java.util.List;
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
   * Los no leídos de cada canal del servidor que esta persona puede leer ahora. Primero se queda
   * con los canales que {@link AccessPolicy#readableChannels} le deja ver (404 si no es miembro) y
   * recién entonces cuenta: lo que no ve no sale de la base. Quien fue expulsado, o sacado de un
   * privado, no pierde su fila: deja de verse porque el canal ya no está en la lista.
   */
  @Transactional(readOnly = true)
  public UnreadList unread(Long userId, Long serverId) {
    List<Long> visible =
        policy.readableChannels(userId, serverId).stream().map(Channel::getId).toList();
    return new UnreadList(readState.countFor(userId, visible));
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
