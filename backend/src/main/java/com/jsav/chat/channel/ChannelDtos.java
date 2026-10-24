package com.jsav.chat.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ChannelDtos {

  private ChannelDtos() {}

  public record CreateChannelRequest(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Pattern(regexp = "text|private", message = "debe ser text o private")
          String kind) {}

  public record AddChannelMemberRequest(@NotNull Long userId) {}

  public record ChannelResponse(Long id, Long serverId, String name, String kind) {
    static ChannelResponse from(Channel c) {
      return new ChannelResponse(c.getId(), c.getServerId(), c.getName(), c.kind().dbValue());
    }
  }
}
