package com.jsav.chat.typing;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TypingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class TypingControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean TypingService service;

  @Test
  void requiereSesion() throws Exception {
    mvc.perform(post("/channels/3/typing")).andExpect(status().isUnauthorized());
  }

  @Test
  void responde_204_y_avisa_al_servicio() throws Exception {
    mvc.perform(post("/channels/3/typing").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNoContent());

    verify(service).typing(7L, 3L);
  }

  @Test
  void canal_ajeno_es_404() throws Exception {
    doThrow(new NotFoundException("Canal no encontrado")).when(service).typing(7L, 3L);

    mvc.perform(post("/channels/3/typing").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound());
  }
}
