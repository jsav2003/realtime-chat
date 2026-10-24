package com.jsav.chat.server;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.channel.Channel;
import com.jsav.chat.channel.ChannelKind;
import com.jsav.chat.channel.ChannelMemberRepository;
import com.jsav.chat.channel.ChannelRepository;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.ServerDtos.ChangeRoleRequest;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.MemberResponse;
import com.jsav.chat.server.ServerDtos.ServerDetailResponse;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServerService {

  private final ServerRepository servers;
  private final ServerMemberRepository members;
  private final ChannelRepository channels;
  private final ChannelMemberRepository channelMembers;
  private final AccessPolicy policy;

  public ServerService(
      ServerRepository servers,
      ServerMemberRepository members,
      ChannelRepository channels,
      ChannelMemberRepository channelMembers,
      AccessPolicy policy) {
    this.servers = servers;
    this.members = members;
    this.channels = channels;
    this.channelMembers = channelMembers;
    this.policy = policy;
  }

  /** El servidor, su owner y el canal #general se guardan en la misma transacción. */
  @Transactional
  public ServerResponse create(Long userId, CreateServerRequest req) {
    ChatServer server = servers.save(new ChatServer(req.name().trim(), userId));
    members.save(new ServerMember(server.getId(), userId, Role.OWNER));
    channels.save(new Channel(server.getId(), "general", ChannelKind.TEXT));
    return ServerResponse.from(server);
  }

  @Transactional(readOnly = true)
  public List<ServerResponse> listMine(Long userId) {
    return servers.findByMember(userId).stream().map(ServerResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public ServerDetailResponse get(Long userId, Long serverId) {
    Role role = policy.requireServer(userId, serverId, ServerAction.VIEW);
    ChatServer server =
        servers
            .findById(serverId)
            .orElseThrow(() -> new NotFoundException("Servidor no encontrado"));
    return new ServerDetailResponse(
        server.getId(), server.getName(), server.getOwnerId(), role.dbValue());
  }

  @Transactional(readOnly = true)
  public List<MemberResponse> listMembers(Long userId, Long serverId) {
    policy.requireServer(userId, serverId, ServerAction.VIEW);
    return members.findMembers(serverId).stream()
        .map(m -> new MemberResponse(m.getUserId(), m.getDisplayName(), m.getRole()))
        .toList();
  }

  @Transactional
  public MemberResponse changeRole(
      Long actorId, Long serverId, Long targetId, ChangeRoleRequest req) {
    ServerMember target =
        policy.requireCanActOn(actorId, serverId, targetId, ServerAction.CHANGE_ROLE);
    target.setRole(Role.fromDb(req.role()));
    members.saveAndFlush(target);
    return members.findMembers(serverId).stream()
        .filter(m -> m.getUserId().equals(targetId))
        .findFirst()
        .map(m -> new MemberResponse(m.getUserId(), m.getDisplayName(), m.getRole()))
        .orElseThrow();
  }

  /**
   * Saca al miembro del servidor y de sus canales privados en una sola transacción; si no, al
   * volver con otra invitación recuperaría los privados. Cerrar sus WebSockets abiertos llega en el
   * hito 4.
   */
  @Transactional
  public void kick(Long actorId, Long serverId, Long targetId) {
    policy.requireCanActOn(actorId, serverId, targetId, ServerAction.KICK);
    channelMembers.removeFromServer(serverId, targetId);
    members.deleteMember(serverId, targetId);
  }
}
