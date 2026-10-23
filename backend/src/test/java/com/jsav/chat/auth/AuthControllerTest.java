package com.jsav.chat.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.auth.AuthDtos.AuthResponse;
import com.jsav.chat.auth.AuthDtos.UserResponse;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.UnauthorizedException;
import com.jsav.chat.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AuthControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;

  private static final String VALID =
      "{\"email\":\"ana@x.com\",\"password\":\"password1\",\"displayName\":\"Ana\"}";

  @Test
  void register_returns201WithoutAuthentication() throws Exception {
    when(auth.register(any()))
        .thenReturn(new AuthResponse("tok", new UserResponse(1L, "ana@x.com", "Ana")));

    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token").value("tok"));
  }

  @Test
  void register_returns400WithFieldErrors() throws Exception {
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"no-es-correo\",\"password\":\"corta\",\"displayName\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("validation_failed"))
        .andExpect(jsonPath("$.fields.email").exists())
        .andExpect(jsonPath("$.fields.password").exists())
        .andExpect(jsonPath("$.fields.displayName").exists());
  }

  @Test
  void register_returns409WhenEmailTaken() throws Exception {
    when(auth.register(any())).thenThrow(new ConflictException("Ese correo ya está registrado"));

    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("conflict"));
  }

  @Test
  void login_returns401OnBadCredentials() throws Exception {
    when(auth.login(any())).thenThrow(new UnauthorizedException("Correo o contraseña incorrectos"));

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@x.com\",\"password\":\"mala\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("unauthorized"));
  }
}
