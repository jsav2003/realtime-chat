package com.jsav.chat.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

  private AuthDtos() {}

  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotBlank @Size(max = 50) String displayName) {}

  public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

  public record UserResponse(Long id, String email, String displayName) {}

  public record AuthResponse(String token, UserResponse user) {}
}
