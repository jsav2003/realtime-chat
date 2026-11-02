package com.jsav.chat.auth;

import com.jsav.chat.auth.AuthDtos.AuthResponse;
import com.jsav.chat.auth.AuthDtos.LoginRequest;
import com.jsav.chat.auth.AuthDtos.RegisterRequest;
import com.jsav.chat.auth.AuthDtos.UserResponse;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.UnauthorizedException;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private static final String EMAIL_TAKEN = "Ese correo ya está registrado";
  private static final String USERNAME_TAKEN = "Ese nombre de usuario ya está en uso";

  /**
   * Nadie puede registrarse con estos: quedan libres para avisos a todo un canal
   * ({@code @everyone}, {@code @here}), que no entran hasta la v2 (docs/v2.md).
   */
  private static final Set<String> RESERVED_USERNAMES = Set.of("everyone", "here", "channel");

  private final UserRepository users;
  private final PasswordEncoder encoder;
  private final JwtService jwt;

  public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
    this.users = users;
    this.encoder = encoder;
    this.jwt = jwt;
  }

  public AuthResponse register(RegisterRequest req) {
    String email = req.email().trim();
    String username = req.username();
    if (RESERVED_USERNAMES.contains(username.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException("Ese nombre de usuario está reservado");
    }
    if (users.existsByEmail(email)) {
      throw new ConflictException(EMAIL_TAKEN);
    }
    if (users.existsByUsername(username)) {
      throw new ConflictException(USERNAME_TAKEN);
    }
    User user;
    try {
      user =
          users.saveAndFlush(
              new User(email, encoder.encode(req.password()), req.displayName().trim(), username));
    } catch (DataIntegrityViolationException e) {
      // Dos registros simultáneos: el índice único decide quién gana. Hay dos índices (correo y
      // username); si ya existe el username fue ese el que chocó, y si no, fue el correo.
      throw new ConflictException(users.existsByUsername(username) ? USERNAME_TAKEN : EMAIL_TAKEN);
    }
    return respond(user);
  }

  public AuthResponse login(LoginRequest req) {
    User user =
        users
            .findByEmail(req.email().trim())
            .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
            .orElseThrow(() -> new UnauthorizedException("Correo o contraseña incorrectos"));
    return respond(user);
  }

  private AuthResponse respond(User user) {
    return new AuthResponse(
        jwt.issue(user),
        new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getUsername()));
  }
}
