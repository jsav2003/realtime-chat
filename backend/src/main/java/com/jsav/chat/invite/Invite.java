package com.jsav.chat.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code uses} lo mueve el SQL atómico de {@link InviteService}, no JPA. */
@Entity
@Table(name = "invites")
public class Invite {

  @Id private String code;

  @Column(name = "server_id", nullable = false)
  private Long serverId;

  @Column(name = "created_by", nullable = false)
  private Long createdBy;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "max_uses")
  private Integer maxUses;

  @Column(nullable = false)
  private int uses;

  protected Invite() {}

  public Invite(String code, Long serverId, Long createdBy, Instant expiresAt, Integer maxUses) {
    this.code = code;
    this.serverId = serverId;
    this.createdBy = createdBy;
    this.expiresAt = expiresAt;
    this.maxUses = maxUses;
  }

  public String getCode() {
    return code;
  }

  public Long getServerId() {
    return serverId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Integer getMaxUses() {
    return maxUses;
  }

  public int getUses() {
    return uses;
  }
}
