package com.jsav.chat.notification;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import com.jsav.chat.notification.NotificationDtos.NotificationItem;
import com.jsav.chat.notification.NotificationDtos.NotificationList;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class NotificationControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean NotificationService service;

  private static NotificationItem item() {
    return new NotificationItem(
        5,
        40,
        3,
        "general",
        1L,
        12,
        "Ana",
        "hola @beto",
        Instant.parse("2026-11-01T10:00:00Z"),
        false);
  }

  @Test
  void everyEndpointNeedsASession() throws Exception {
    mvc.perform(get("/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(put("/notifications/5/read")).andExpect(status().isUnauthorized());
    mvc.perform(post("/notifications/read-all")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_returnsTheCountAndTheItems_withTheDefaultLimit() throws Exception {
    when(service.list(7L, 20)).thenReturn(new NotificationList(3, List.of(item())));

    mvc.perform(get("/notifications").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unreadCount").value(3))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].channelName").value("general"))
        .andExpect(jsonPath("$.items[0].authorName").value("Ana"))
        .andExpect(jsonPath("$.items[0].read").value(false));
  }

  @Test
  void list_passesTheRequestedLimit() throws Exception {
    when(service.list(7L, 5)).thenReturn(new NotificationList(0, List.of()));

    mvc.perform(get("/notifications?limit=5").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk());

    verify(service).list(7L, 5);
  }

  @Test
  void list_withALimitOutOfRange_is400() throws Exception {
    when(service.list(7L, 0)).thenThrow(new BadRequestException("limit debe estar entre 1 y 100"));

    mvc.perform(get("/notifications?limit=0").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void markRead_answers204_andTellsTheServiceWhoAsked() throws Exception {
    mvc.perform(put("/notifications/5/read").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNoContent());

    verify(service).markRead(7L, 5L);
  }

  @Test
  void markRead_ofSomeoneElsesNotification_is404() throws Exception {
    doThrow(new NotFoundException("Notificación no encontrada")).when(service).markRead(7L, 5L);

    mvc.perform(put("/notifications/5/read").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void readAll_answers204() throws Exception {
    mvc.perform(post("/notifications/read-all").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNoContent());

    verify(service).markAllRead(7L);
  }
}
