package com.jsav.chat.channel;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.channel.ChannelDtos.ChannelResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerMemberId;
import com.jsav.chat.server.ServerMemberRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelService {

  private final ChannelRepository channels;
  private final ChannelMemberRepository channelMembers;
  private final ServerMemberRepository serverMembers;
  private final AccessPolicy policy;

  public ChannelService(
      ChannelRepository channels,
      ChannelMemberRepository channelMembers,
      ServerMemberRepository serverMembers,
      AccessPolicy policy) {
    this.channels = channels;
    this.channelMembers = channelMembers;
    this.serverMembers = serverMembers;
    this.policy = policy;
  }

  /** Owner y admin ven todos; un member, los públicos y los privados donde está. */
  @Transactional(readOnly = true)
  public List<ChannelResponse> list(Long userId, Long serverId) {
    Role role = policy.requireServer(userId, serverId, ServerAction.VIEW);
    List<Channel> visible =
        role.canAdminister()
            ? channels.findByServerIdOrderById(serverId)
            : channels.findVisibleTo(serverId, userId);
    return visible.stream().map(ChannelResponse::from).toList();
  }

  @Transactional
  public ChannelResponse create(Long userId, Long serverId, CreateChannelRequest req) {
    policy.requireServer(userId, serverId, ServerAction.MANAGE_CHANNELS);
    String name = req.name().trim();
    ChannelKind kind = ChannelKind.fromDb(req.kind());
    if (channels.existsByServerIdAndName(serverId, name)) {
      throw new ConflictException("Ya existe un canal con ese nombre");
    }
    Channel channel;
    try {
      channel = channels.saveAndFlush(new Channel(serverId, name, kind));
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("Ya existe un canal con ese nombre");
    }
    if (kind == ChannelKind.PRIVATE) {
      channelMembers.add(channel.getId(), userId);
    }
    return ChannelResponse.from(channel);
  }

  @Transactional(readOnly = true)
  public ChannelResponse get(Long userId, Long channelId) {
    Channel channel = policy.requireChannel(userId, channelId, ChannelAction.READ).channel();
    return ChannelResponse.from(channel);
  }

  @Transactional
  public void addMember(Long actorId, Long channelId, Long targetUserId) {
    Channel channel = requirePrivateManageable(actorId, channelId);
    if (!serverMembers.existsById(new ServerMemberId(channel.getServerId(), targetUserId))) {
      throw new NotFoundException("Miembro no encontrado");
    }
    channelMembers.add(channelId, targetUserId);
  }

  @Transactional
  public void removeMember(Long actorId, Long channelId, Long targetUserId) {
    requirePrivateManageable(actorId, channelId);
    if (channelMembers.remove(channelId, targetUserId) == 0) {
      throw new NotFoundException("No es miembro del canal");
    }
  }

  private Channel requirePrivateManageable(Long actorId, Long channelId) {
    Channel channel =
        policy.requireChannel(actorId, channelId, ChannelAction.MANAGE_MEMBERS).channel();
    if (channel.kind() != ChannelKind.PRIVATE) {
      throw new ConflictException("Solo los canales privados tienen lista de miembros");
    }
    return channel;
  }
}
