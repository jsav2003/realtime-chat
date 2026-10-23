package com.jsav.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jsav.chat.auth.AuthDtos.AuthResponse;
import com.jsav.chat.auth.AuthDtos.LoginRequest;
import com.jsav.chat.auth.AuthDtos.RegisterRequest;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.UnauthorizedException;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock UserRepository users;
  @Mock PasswordEncoder encoder;
  @Mock JwtService jwt;
  @InjectMocks AuthService service;

  @Test
  void register_rejectsEmailAlreadyTaken() {
    when(users.existsByEmail("ana@x.com")).thenReturn(true);

    assertThatThrownBy(() -> service.register(new RegisterRequest("ana@x.com", "password1", "Ana")))
        .isInstanceOf(ConflictException.class);
    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void register_turnsUniqueIndexRaceIntoConflict() {
    when(users.existsByEmail("ana@x.com")).thenReturn(false);
    when(encoder.encode("password1")).thenReturn("hash");
    when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertThatThrownBy(() -> service.register(new RegisterRequest("ana@x.com", "password1", "Ana")))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void register_storesHashNeverThePassword() {
    when(users.existsByEmail("ana@x.com")).thenReturn(false);
    when(encoder.encode("password1")).thenReturn("hash");
    when(users.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(jwt.issue(any())).thenReturn("token");

    AuthResponse res = service.register(new RegisterRequest(" ana@x.com ", "password1", "Ana"));

    assertThat(res.token()).isEqualTo("token");
    assertThat(res.user().email()).isEqualTo("ana@x.com");
    verify(users)
        .saveAndFlush(
            org.mockito.ArgumentMatchers.argThat(u -> u.getPasswordHash().equals("hash")));
  }

  @Test
  void login_failsWithSameMessageForUnknownEmailAndWrongPassword() {
    when(users.findByEmail("nadie@x.com")).thenReturn(Optional.empty());
    User ana = new User("ana@x.com", "hash", "Ana");
    when(users.findByEmail("ana@x.com")).thenReturn(Optional.of(ana));
    when(encoder.matches("mala", "hash")).thenReturn(false);

    Throwable unknown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.login(new LoginRequest("nadie@x.com", "password1")));
    Throwable wrong =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> service.login(new LoginRequest("ana@x.com", "mala")));

    assertThat(unknown).isInstanceOf(UnauthorizedException.class);
    assertThat(wrong).isInstanceOf(UnauthorizedException.class);
    assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
  }
}
