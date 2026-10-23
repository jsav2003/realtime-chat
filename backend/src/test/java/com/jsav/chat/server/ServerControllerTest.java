package com.jsav.chat.server;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.config.SecurityConfig;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ServerController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ServerControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean ServerService service;

  @Test
  void list_requiresAuthentication() throws Exception {
    mvc.perform(get("/servers")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_usesUserIdFromTokenSubject() throws Exception {
    when(service.listMine(7L)).thenReturn(List.of(new ServerResponse(1L, "A", 7L)));

    mvc.perform(get("/servers").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("A"));
  }

  @Test
  void create_returns201() throws Exception {
    when(service.create(7L, new CreateServerRequest("Nuevo")))
        .thenReturn(new ServerResponse(3L, "Nuevo", 7L));

    mvc.perform(
            post("/servers")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Nuevo\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(3));
  }

  @Test
  void create_rejectsBlankName() throws Exception {
    mvc.perform(
            post("/servers")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.name").exists());
  }
}
