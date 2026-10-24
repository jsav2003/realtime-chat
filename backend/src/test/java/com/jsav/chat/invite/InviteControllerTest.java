package com.jsav.chat.invite;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import com.jsav.chat.invite.InviteDtos.CreateInviteRequest;
import com.jsav.chat.invite.InviteDtos.InvitePreviewResponse;
import com.jsav.chat.invite.InviteDtos.InviteResponse;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InviteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InviteControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean InviteService service;

  @Test
  void everyInviteEndpointRequiresAuthentication() throws Exception {
    mvc.perform(post("/servers/1/invites")).andExpect(status().isUnauthorized());
    mvc.perform(get("/invites/abc")).andExpect(status().isUnauthorized());
    mvc.perform(post("/invites/abc/accept")).andExpect(status().isUnauthorized());
  }

  @Test
  void create_acceptsEmptyBody_andReturnsPathToFrontend() throws Exception {
    when(service.create(7L, 1L, new CreateInviteRequest(null, null)))
        .thenReturn(new InviteResponse("AbC123xyz0", "/invite/AbC123xyz0", null, null, 0));

    mvc.perform(post("/servers/1/invites").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("AbC123xyz0"))
        .andExpect(jsonPath("$.path").value("/invite/AbC123xyz0"));
  }

  @Test
  void create_passesLimits() throws Exception {
    when(service.create(7L, 1L, new CreateInviteRequest(24, 5)))
        .thenReturn(new InviteResponse("c", "/invite/c", null, 5, 0));

    mvc.perform(
            post("/servers/1/invites")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInHours\":24,\"maxUses\":5}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.maxUses").value(5));
  }

  @Test
  void create_rejectsNonPositiveLimits() throws Exception {
    mvc.perform(
            post("/servers/1/invites")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInHours\":0,\"maxUses\":-1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.expiresInHours").exists())
        .andExpect(jsonPath("$.fields.maxUses").exists());
  }

  @Test
  void preview_returnsServerName_andInvalidCodeIs404() throws Exception {
    when(service.preview("good")).thenReturn(new InvitePreviewResponse("good", "Mi servidor"));
    when(service.preview("bad")).thenThrow(new NotFoundException("Invitación no válida"));

    mvc.perform(get("/invites/good").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverName").value("Mi servidor"));
    mvc.perform(get("/invites/bad").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void accept_usesUserIdFromTokenSubject() throws Exception {
    when(service.accept(7L, "good")).thenReturn(new ServerResponse(1L, "Mi servidor", 2L));

    mvc.perform(post("/invites/good/accept").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Mi servidor"));
    verify(service).accept(7L, "good");
  }
}
