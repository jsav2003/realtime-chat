package com.jsav.chat.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ServerDtos {

  private ServerDtos() {}

  public record CreateServerRequest(@NotBlank @Size(max = 100) String name) {}

  public record ServerResponse(Long id, String name, Long ownerId) {
    public static ServerResponse from(ChatServer s) {
      return new ServerResponse(s.getId(), s.getName(), s.getOwnerId());
    }
  }

  /** Detalle del servidor, con el rol de quien pregunta. */
  public record ServerDetailResponse(Long id, String name, Long ownerId, String myRole) {}

  public record MemberResponse(Long userId, String displayName, String role) {}

  /** Solo se puede pasar a admin o member: owner no se asigna. */
  public record ChangeRoleRequest(
      @NotNull @Pattern(regexp = "admin|member", message = "debe ser admin o member")
          String role) {}
}
