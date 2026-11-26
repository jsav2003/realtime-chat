package com.jsav.chat.search;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import com.jsav.chat.search.SearchDtos.SearchPage;
import com.jsav.chat.search.SearchDtos.SearchResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class SearchControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean SearchService service;

  @Test
  void itNeedsASession() throws Exception {
    mvc.perform(get("/servers/4/search").param("q", "hola")).andExpect(status().isUnauthorized());
  }

  @Test
  void passesTheQueryAndTheDefaults_andReturnsThePage() throws Exception {
    when(service.search(7L, 4L, "hola", null, null, SearchService.DEFAULT_LIMIT))
        .thenReturn(
            new SearchPage(
                List.of(
                    new SearchResult(
                        30,
                        3,
                        "general",
                        12,
                        "Ana",
                        Instant.parse("2026-11-25T12:00:00Z"),
                        "hola a todos")),
                true));

    mvc.perform(get("/servers/4/search").param("q", "hola").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.results.length()").value(1))
        .andExpect(jsonPath("$.results[0].messageId").value(30))
        .andExpect(jsonPath("$.results[0].channelId").value(3))
        .andExpect(jsonPath("$.results[0].channelName").value("general"))
        .andExpect(jsonPath("$.results[0].seq").value(12))
        .andExpect(jsonPath("$.results[0].authorName").value("Ana"))
        .andExpect(jsonPath("$.results[0].snippet").value("hola a todos"));
  }

  @Test
  void passesTheChannelTheCursorAndTheLimit() throws Exception {
    when(service.search(7L, 4L, "hola", 3L, 99L, 5)).thenReturn(new SearchPage(List.of(), false));

    mvc.perform(
            get("/servers/4/search")
                .param("q", "hola")
                .param("channel_id", "3")
                .param("before_id", "99")
                .param("limit", "5")
                .with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk());

    verify(service).search(7L, 4L, "hola", 3L, 99L, 5);
  }

  @Test
  void whatTheServiceRejects_is400() throws Exception {
    when(service.search(7L, 4L, null, null, null, SearchService.DEFAULT_LIMIT))
        .thenThrow(new BadRequestException("q debe tener entre 1 y 200 caracteres"));

    mvc.perform(get("/servers/4/search").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aServerYouAreNotIn_is404() throws Exception {
    when(service.search(7L, 4L, "hola", null, null, SearchService.DEFAULT_LIMIT))
        .thenThrow(new NotFoundException("Servidor no encontrado"));

    mvc.perform(get("/servers/4/search").param("q", "hola").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void aChannelIdThatIsNotANumber_is400() throws Exception {
    mvc.perform(
            get("/servers/4/search")
                .param("q", "hola")
                .param("channel_id", "abc")
                .with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isBadRequest());
  }
}
