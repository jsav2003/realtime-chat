package com.jsav.chat.access;

import com.jsav.chat.channel.Channel;
import com.jsav.chat.channel.ChannelKind;
import com.jsav.chat.channel.ChannelMemberId;
import com.jsav.chat.channel.ChannelMemberRepository;
import com.jsav.chat.channel.ChannelRepository;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberId;
import com.jsav.chat.server.ServerMemberRepository;
import org.springframework.stereotype.Component;

/**
 * Único lugar donde se decide si alguien puede hacer algo (DESIGN.md §4). Los controladores y
 * servicios HTTP la llaman; el interceptor STOMP del hito 4 llamará a la misma.
 *
 * <p>404 si el usuario no puede ver el recurso (para no confirmar que existe), 403 si lo ve pero no
 * puede hacer la acción. Lee siempre de la base: nada de roles dentro del JWT, así que quien es
 * expulsado queda fuera en la siguiente solicitud aunque su token siga vigente.
 */
@Component
public class AccessPolicy {

  /** Canal al que el usuario tiene acceso, con el rol que tiene en su servidor. */
  public record ChannelAccess(Channel channel, Role role) {}

  private final ServerMemberRepository members;
  private final ChannelRepository channels;
  private final ChannelMemberRepository channelMembers;

  public AccessPolicy(
      ServerMemberRepository members,
      ChannelRepository channels,
      ChannelMemberRepository channelMembers) {
    this.members = members;
    this.channels = channels;
    this.channelMembers = channelMembers;
  }

  /** Devuelve el rol del usuario en el servidor si puede hacer la acción. */
  public Role requireServer(Long userId, Long serverId, ServerAction action) {
    Role role =
        members
            .findById(new ServerMemberId(serverId, userId))
            .map(ServerMember::role)
            .orElseThrow(() -> new NotFoundException("Servidor no encontrado"));
    if (!action.allows(role)) {
      throw new ForbiddenException("No tienes permiso para hacer esto en este servidor");
    }
    return role;
  }

  public ChannelAccess requireChannel(Long userId, Long channelId, ChannelAction action) {
    Channel channel =
        channels
            .findById(channelId)
            .orElseThrow(() -> new NotFoundException("Canal no encontrado"));
    if (channel.kind() == ChannelKind.DM) {
      // Los DM no tienen servidor ni roles: solo cuenta estar en channel_members.
      if (!isChannelMember(channelId, userId)) {
        throw new NotFoundException("Canal no encontrado");
      }
      return new ChannelAccess(channel, Role.MEMBER);
    }

    Role role =
        members
            .findById(new ServerMemberId(channel.getServerId(), userId))
            .map(ServerMember::role)
            .orElseThrow(() -> new NotFoundException("Canal no encontrado"));
    boolean sees =
        channel.kind() == ChannelKind.TEXT
            || role.canAdminister()
            || isChannelMember(channelId, userId);
    if (!sees) {
      throw new NotFoundException("Canal no encontrado");
    }
    if (!action.allows(role)) {
      throw new ForbiddenException("No tienes permiso para hacer esto en este canal");
    }
    return new ChannelAccess(channel, role);
  }

  /**
   * Para expulsar o cambiar el rol de otro miembro. Además del permiso sobre el servidor: el owner
   * es intocable, nadie actúa sobre sí mismo, y un admin solo puede expulsar a members. Devuelve el
   * miembro objetivo.
   */
  public ServerMember requireCanActOn(
      Long actorId, Long serverId, Long targetId, ServerAction action) {
    Role actorRole = requireServer(actorId, serverId, action);
    ServerMember target =
        members
            .findById(new ServerMemberId(serverId, targetId))
            .orElseThrow(() -> new NotFoundException("Miembro no encontrado"));
    if (actorId.equals(targetId)) {
      throw new ForbiddenException("No puedes hacerlo sobre ti mismo");
    }
    if (target.role() == Role.OWNER) {
      throw new ForbiddenException("El owner no se puede modificar");
    }
    if (action == ServerAction.KICK && actorRole == Role.ADMIN && target.role() == Role.ADMIN) {
      throw new ForbiddenException("Un admin no puede expulsar a otro admin");
    }
    return target;
  }

  private boolean isChannelMember(Long channelId, Long userId) {
    return channelMembers.existsById(new ChannelMemberId(channelId, userId));
  }
}
