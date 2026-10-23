package com.jsav.chat.server;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "server_members")
public class ServerMember {

  @EmbeddedId private ServerMemberId id;

  @Column(nullable = false)
  private String role;

  protected ServerMember() {}

  public ServerMember(Long serverId, Long userId, Role role) {
    this.id = new ServerMemberId(serverId, userId);
    this.role = role.dbValue();
  }

  public ServerMemberId getId() {
    return id;
  }

  public String getRole() {
    return role;
  }
}
