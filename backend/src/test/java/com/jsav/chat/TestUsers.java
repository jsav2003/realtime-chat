package com.jsav.chat;

import com.jsav.chat.user.User;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Usuarios para los tests que no se fijan en el username: cada uno recibe uno distinto, porque la
 * base es la misma para todos y el handle es único. Los tests de menciones, que sí necesitan un
 * nombre concreto, usan el constructor de {@link User} con el username a la vista.
 */
public final class TestUsers {

  /** Distinto en cada JVM, por si dos corridas comparten una base. Cabe en los 20 caracteres. */
  private static final String PREFIX = "t" + UUID.randomUUID().toString().substring(0, 6);

  private static final AtomicLong NEXT = new AtomicLong();

  private TestUsers() {}

  public static User user(String email, String passwordHash, String displayName) {
    return new User(email, passwordHash, displayName, PREFIX + "_" + NEXT.incrementAndGet());
  }
}
