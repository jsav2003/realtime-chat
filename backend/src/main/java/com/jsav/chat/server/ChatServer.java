package com.jsav.chat.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "servers")
public class ChatServer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String name;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  protected ChatServer() {}

  public ChatServer(String name, Long ownerId) {
    this.name = name;
    this.ownerId = ownerId;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Long getOwnerId() {
    return ownerId;
  }
}
