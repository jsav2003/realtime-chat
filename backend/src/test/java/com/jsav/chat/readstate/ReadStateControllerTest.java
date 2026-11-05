package com.jsav.chat.readstate;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReadStateController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ReadStateControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean ReadStateService service;

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mark(
      String body) {
    return put("/channels/3/read")
        .with(jwt().jwt(j -> j.subject("7")))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  void itNeedsASession() throws Exception {
    mvc.perform(
            put("/channels/3/read").contentType(MediaType.APPLICATION_JSON).content("{\"seq\":1}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void answers204_andTellsTheServiceWhoAskedAndUpToWhere() throws Exception {
    mvc.perform(mark("{\"seq\":12}")).andExpect(status().isNoContent());

    verify(service).markRead(7L, 3L, 12L);
  }

  @Test
  void aNegativeSeq_is400_andNothingIsMarked() throws Exception {
    mvc.perform(mark("{\"seq\":-1}")).andExpect(status().isBadRequest());

    verify(service, never())
        .markRead(
            ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong());
  }

  @Test
  void aMissingSeq_is400() throws Exception {
    mvc.perform(mark("{}")).andExpect(status().isBadRequest());
  }

  @Test
  void aChannelYouCannotSee_is404() throws Exception {
    doThrow(new NotFoundException("Canal no encontrado")).when(service).markRead(7L, 3L, 1L);

    mvc.perform(mark("{\"seq\":1}")).andExpect(status().isNotFound());
  }
}
