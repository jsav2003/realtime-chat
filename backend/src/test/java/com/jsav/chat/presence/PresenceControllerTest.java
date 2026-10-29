package com.jsav.chat.presence;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.common.ServiceUnavailableException;
import com.jsav.chat.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PresenceController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class PresenceControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean PresenceService service;

  @Test
  void requiereSesion() throws Exception {
    mvc.perform(get("/servers/3/presence")).andExpect(status().isUnauthorized());
  }

  @Test
  void devuelve_los_ids_en_linea() throws Exception {
    when(service.online(7L, 3L)).thenReturn(List.of(4L, 9L));

    mvc.perform(get("/servers/3/presence").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.online[0]").value(4))
        .andExpect(jsonPath("$.online[1]").value(9));
  }

  @Test
  void servidor_ajeno_es_404() throws Exception {
    when(service.online(7L, 3L)).thenThrow(new NotFoundException("Servidor no encontrado"));

    mvc.perform(get("/servers/3/presence").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void sin_redis_es_503() throws Exception {
    when(service.online(7L, 3L))
        .thenThrow(new ServiceUnavailableException("La presencia no está disponible", null));

    mvc.perform(get("/servers/3/presence").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("service_unavailable"));
  }
}
