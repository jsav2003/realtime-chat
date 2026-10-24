package com.jsav.chat.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** {@code last_seq} no se mapea: lo mueve el hito 3 con SQL atómico, no con JPA. */
@Entity
@Table(name = "channels")
public class Channel {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Null solo en los mensajes directos. */
  @Column(name = "server_id")
  private Long serverId;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private String kind;

  protected Channel() {}

  public Channel(Long serverId, String name, ChannelKind kind) {
    this.serverId = serverId;
    this.name = name;
    this.kind = kind.dbValue();
  }

  public Long getId() {
    return id;
  }

  public Long getServerId() {
    return serverId;
  }

  public String getName() {
    return name;
  }

  public ChannelKind kind() {
    return ChannelKind.fromDb(kind);
  }
}
