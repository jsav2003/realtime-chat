package com.jsav.chat.invite;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ServerAction;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.invite.InviteDtos.CreateInviteRequest;
import com.jsav.chat.invite.InviteDtos.InvitePreviewResponse;
import com.jsav.chat.invite.InviteDtos.InviteResponse;
import com.jsav.chat.server.ChatServer;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import com.jsav.chat.server.ServerMemberId;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int CODE_LENGTH = 10;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final InviteRepository invites;
  private final ServerRepository servers;
  private final ServerMemberRepository members;
  private final AccessPolicy policy;
  private final JdbcClient jdbc;

  public InviteService(
      InviteRepository invites,
      ServerRepository servers,
      ServerMemberRepository members,
      AccessPolicy policy,
      JdbcClient jdbc) {
    this.invites = invites;
    this.servers = servers;
    this.members = members;
    this.policy = policy;
    this.jdbc = jdbc;
  }

  @Transactional
  public InviteResponse create(Long userId, Long serverId, CreateInviteRequest req) {
    policy.requireServer(userId, serverId, ServerAction.CREATE_INVITE);
    Instant expiresAt =
        req.expiresInHours() == null
            ? null
            : Instant.now().plus(req.expiresInHours(), ChronoUnit.HOURS);
    Invite invite = invites.save(new Invite(newCode(), serverId, userId, expiresAt, req.maxUses()));
    return InviteResponse.from(invite);
  }

  /** Vista previa para quien abre el link: vencida, agotada o inexistente dan lo mismo, 404. */
  @Transactional(readOnly = true)
  public InvitePreviewResponse preview(String code) {
    Invite invite = invites.findUsable(code, Instant.now()).orElseThrow(InviteService::invalid);
    ChatServer server = servers.findById(invite.getServerId()).orElseThrow(InviteService::invalid);
    return new InvitePreviewResponse(invite.getCode(), server.getName());
  }

  /**
   * Gasta un uso y agrega al usuario como member. El {@code UPDATE ... WHERE uses < max_uses} es
   * atómico: con {@code max_uses = 1} y diez usuarios a la vez entra exactamente uno, porque los
   * demás esperan el lock de la fila y al releerla ya no cumplen la condición (igual que el {@code
   * seq} de DESIGN.md §3.2).
   */
  @Transactional
  public ServerResponse accept(Long userId, String code) {
    Invite invite = invites.findById(code).orElseThrow(InviteService::invalid);
    Long serverId = invite.getServerId();

    // Quien ya es miembro no gasta un uso.
    if (members.existsById(new ServerMemberId(serverId, userId))) {
      return serverResponse(serverId);
    }

    jdbc.sql(
            """
            update invites set uses = uses + 1
            where code = :code
              and (expires_at is null or expires_at > now())
              and (max_uses is null or uses < max_uses)
            returning server_id
            """)
        .param("code", code)
        .query(Long.class)
        .optional()
        .orElseThrow(InviteService::invalid);

    int inserted =
        jdbc.sql(
                """
                insert into server_members (server_id, user_id, role)
                values (:serverId, :userId, 'member')
                on conflict do nothing
                """)
            .param("serverId", serverId)
            .param("userId", userId)
            .update();
    if (inserted == 0) {
      // Entró por otra vía entre la comprobación y el insert: devolver el uso.
      jdbc.sql("update invites set uses = uses - 1 where code = :code")
          .param("code", code)
          .update();
    }
    return serverResponse(serverId);
  }

  private ServerResponse serverResponse(Long serverId) {
    ChatServer server = servers.findById(serverId).orElseThrow(InviteService::invalid);
    return ServerResponse.from(server);
  }

  private static NotFoundException invalid() {
    return new NotFoundException("Invitación no válida");
  }

  private static String newCode() {
    StringBuilder sb = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}
