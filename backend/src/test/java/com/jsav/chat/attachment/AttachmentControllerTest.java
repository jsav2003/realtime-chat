package com.jsav.chat.attachment;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.attachment.AttachmentDtos.UploadRequest;
import com.jsav.chat.attachment.AttachmentDtos.UploadResponse;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.config.SecurityConfig;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AttachmentController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AttachmentControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean AttachmentService service;

  private static final String BODY =
      "{\"filename\":\"a.png\",\"contentType\":\"image/png\",\"size\":1234}";

  @Test
  void requestUpload_needsASession() throws Exception {
    mvc.perform(
            post("/channels/3/attachments").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void requestUpload_returns201WithTheUrlAndTheSignedHeaders() throws Exception {
    when(service.requestUpload(7L, 3L, new UploadRequest("a.png", "image/png", 1234)))
        .thenReturn(
            new UploadResponse(
                11L,
                "http://s3/chat/x?sig=1",
                Map.of("content-type", "image/png"),
                Instant.parse("2026-11-03T10:00:00Z")));

    mvc.perform(
            post("/channels/3/attachments")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(11))
        .andExpect(jsonPath("$.uploadUrl").value("http://s3/chat/x?sig=1"))
        .andExpect(jsonPath("$.headers['content-type']").value("image/png"));
  }

  @Test
  void requestUpload_withASizeOfZeroOrNoName_is400() throws Exception {
    for (String body :
        new String[] {
          "{\"filename\":\"a.png\",\"contentType\":\"image/png\",\"size\":0}",
          "{\"filename\":\"a.png\",\"contentType\":\"image/png\"}",
          "{\"filename\":\"  \",\"contentType\":\"image/png\",\"size\":5}",
          "{\"contentType\":\"image/png\",\"size\":5}"
        }) {
      mvc.perform(
              post("/channels/3/attachments")
                  .with(jwt().jwt(j -> j.subject("7")))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void requestUpload_forAChannelTheUserCannotSee_is404() throws Exception {
    when(service.requestUpload(7L, 3L, new UploadRequest("a.png", "image/png", 1234)))
        .thenThrow(new NotFoundException("Canal no encontrado"));

    mvc.perform(
            post("/channels/3/attachments")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isNotFound());
  }
}
