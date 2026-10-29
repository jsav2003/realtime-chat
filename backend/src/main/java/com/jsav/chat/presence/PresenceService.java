package com.jsav.chat.presence;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.common.ServiceUnavailableException;
import com.jsav.chat.server.ServerMemberRepository;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Quién del servidor está en línea (DESIGN.md §5, ADR 0008). */
@Service
public class PresenceService {

  private final AccessPolicy policy;
  private final ServerMemberRepository members;
  private final PresenceStore store;

  public PresenceService(AccessPolicy policy, ServerMemberRepository members, PresenceStore store) {
    this.policy = policy;
    this.members = members;
    this.store = store;
  }

  /**
   * Los miembros de {@code serverId} con alguna conexión viva, ordenados por id. Solo mira a los
   * miembros de ese servidor: no revela si alguien de fuera está en línea. 404 para quien no es
   * miembro (AccessPolicy). Sin Redis no hay presencia: 503, y el cliente simplemente no pinta los
   * puntos.
   */
  public List<Long> online(Long userId, Long serverId) {
    policy.requireServer(userId, serverId, ServerAction.VIEW);
    List<Long> memberIds = members.findUserIds(serverId);
    try {
      return store.online(memberIds).stream().sorted().toList();
    } catch (DataAccessException e) {
      throw new ServiceUnavailableException("La presencia no está disponible por ahora", e);
    }
  }
}
