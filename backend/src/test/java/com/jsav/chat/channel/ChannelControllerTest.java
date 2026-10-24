package com.jsav.chat.channel;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.channel.ChannelDtos.ChannelResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.common.ConflictException;
import com.jsav.chat.common.ForbiddenException;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChannelController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class ChannelControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean ChannelService service;

  @Test
  void list_requiresAuthentication() throws Exception {
    mvc.perform(get("/servers/1/channels")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_usesUserIdFromTokenSubject() throws Exception {
    when(service.list(7L, 1L)).thenReturn(List.of(new ChannelResponse(5L, 1L, "general", "text")));

    mvc.perform(get("/servers/1/channels").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("general"))
        .andExpect(jsonPath("$[0].kind").value("text"));
  }

  @Test
  void create_returns201() throws Exception {
    when(service.create(7L, 1L, new CreateChannelRequest("secreto", "private")))
        .thenReturn(new ChannelResponse(9L, 1L, "secreto", "private"));

    mvc.perform(
            post("/servers/1/channels")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"secreto\",\"kind\":\"private\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(9));
  }

  @Test
  void create_rejectsUnknownKindAndBlankName() throws Exception {
    mvc.perform(
            post("/servers/1/channels")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"kind\":\"dm\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.name").exists())
        .andExpect(jsonPath("$.fields.kind").exists());
  }

  @Test
  void create_duplicateNameIs409() throws Exception {
    when(service.create(7L, 1L, new CreateChannelRequest("general", "text")))
        .thenThrow(new ConflictException("Ya existe un canal con ese nombre"));

    mvc.perform(
            post("/servers/1/channels")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"general\",\"kind\":\"text\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void create_memberGets403WithApiErrorBody() throws Exception {
    when(service.create(7L, 1L, new CreateChannelRequest("x", "text")))
        .thenThrow(new ForbiddenException("No tienes permiso"));

    mvc.perform(
            post("/servers/1/channels")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"kind\":\"text\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"));
  }

  @Test
  void get_hiddenChannelIs404() throws Exception {
    when(service.get(7L, 3L)).thenThrow(new NotFoundException("Canal no encontrado"));

    mvc.perform(get("/channels/3").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
  }

  @Test
  void addMember_returns204_andRejectsMissingUserId() throws Exception {
    mvc.perform(
            post("/channels/3/members")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":12}"))
        .andExpect(status().isNoContent());
    verify(service).addMember(7L, 3L, 12L);

    mvc.perform(
            post("/channels/3/members")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void removeMember_returns204() throws Exception {
    mvc.perform(delete("/channels/3/members/12").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNoContent());
    verify(service).removeMember(7L, 3L, 12L);
  }
}
