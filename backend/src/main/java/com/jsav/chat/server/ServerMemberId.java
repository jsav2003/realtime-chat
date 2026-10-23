package com.jsav.chat.server;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ServerMemberId implements Serializable {

  @Column(name = "server_id")
  private Long serverId;

  @Column(name = "user_id")
  private Long userId;

  protected ServerMemberId() {}

  public ServerMemberId(Long serverId, Long userId) {
    this.serverId = serverId;
    this.userId = userId;
  }

  public Long getServerId() {
    return serverId;
  }

  public Long getUserId() {
    return userId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ServerMemberId other
        && Objects.equals(serverId, other.serverId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(serverId, userId);
  }
}
