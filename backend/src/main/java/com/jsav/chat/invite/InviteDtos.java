package com.jsav.chat.invite;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;

public final class InviteDtos {

  private InviteDtos() {}

  /** Ambos campos son opcionales: sin ellos la invitación no vence ni se agota. */
  public record CreateInviteRequest(
      @Min(1) @Max(24 * 365) Integer expiresInHours, @Min(1) @Max(1000) Integer maxUses) {}

  /** {@code path} es la ruta del frontend; el backend no conoce el dominio. */
  public record InviteResponse(
      String code, String path, Instant expiresAt, Integer maxUses, int uses) {
    static InviteResponse from(Invite i) {
      return new InviteResponse(
          i.getCode(), "/invite/" + i.getCode(), i.getExpiresAt(), i.getMaxUses(), i.getUses());
    }
  }

  public record InvitePreviewResponse(String code, String serverName) {}
}
