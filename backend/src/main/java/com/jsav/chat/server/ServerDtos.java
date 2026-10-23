package com.jsav.chat.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ServerDtos {

  private ServerDtos() {}

  public record CreateServerRequest(@NotBlank @Size(max = 100) String name) {}

  public record ServerResponse(Long id, String name, Long ownerId) {
    static ServerResponse from(ChatServer s) {
      return new ServerResponse(s.getId(), s.getName(), s.getOwnerId());
    }
  }
}
