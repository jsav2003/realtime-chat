package com.jsav.chat.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  /** El handle de las menciones ({@code @beto}): único sin distinguir mayúsculas. */
  @Column(nullable = false)
  private String username;

  protected User() {}

  public User(String email, String passwordHash, String displayName, String username) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.username = username;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getUsername() {
    return username;
  }
}
