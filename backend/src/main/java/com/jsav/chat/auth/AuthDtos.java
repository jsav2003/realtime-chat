package com.jsav.chat.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

  private AuthDtos() {}

  /**
   * {@code username} es el handle de las menciones ({@code @beto}): de 3 a 20 letras, números o
   * guion bajo, sin espacios. Es único sin distinguir mayúsculas.
   */
  public record RegisterRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 8, max = 72) String password,
      @NotBlank @Size(max = 50) String displayName,
      @NotBlank
          @Pattern(
              regexp = "[A-Za-z0-9_]{3,20}",
              message = "debe tener de 3 a 20 letras, números o guion bajo")
          String username) {}

  public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

  public record UserResponse(Long id, String email, String displayName, String username) {}

  public record AuthResponse(String token, UserResponse user) {}
}
