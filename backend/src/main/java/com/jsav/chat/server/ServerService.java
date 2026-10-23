package com.jsav.chat.server;

import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServerService {

  private final ServerRepository servers;
  private final ServerMemberRepository members;

  public ServerService(ServerRepository servers, ServerMemberRepository members) {
    this.servers = servers;
    this.members = members;
  }

  /** El servidor y su owner se guardan en la misma transacción. */
  @Transactional
  public ServerResponse create(Long userId, CreateServerRequest req) {
    ChatServer server = servers.save(new ChatServer(req.name().trim(), userId));
    members.save(new ServerMember(server.getId(), userId, Role.OWNER));
    return ServerResponse.from(server);
  }

  @Transactional(readOnly = true)
  public List<ServerResponse> listMine(Long userId) {
    return servers.findByMember(userId).stream().map(ServerResponse::from).toList();
  }
}
