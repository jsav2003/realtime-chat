package com.jsav.chat.message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.GlobalExceptionHandler;
import com.jsav.chat.config.SecurityConfig;
import com.jsav.chat.message.MessageDtos.MessagePage;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService.Sent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MessageControllerTest {

  static final UUID KEY = UUID.fromString("0b5a7c1e-8a1f-4a43-9a51-2f1c3e4d5a6b");

  @Autowired MockMvc mvc;
  @MockitoBean MessageService service;

  private static MessageResponse message(long seq) {
    return new MessageResponse(
        40L + seq, 3L, seq, 7L, "Ana", KEY, "hola", Instant.parse("2026-10-26T15:00:00Z"));
  }

  private static String body(String clientMsgId, String text) {
    return "{\"clientMsgId\":%s,\"body\":%s}"
        .formatted(
            clientMsgId == null ? "null" : "\"" + clientMsgId + "\"",
            text == null ? "null" : "\"" + text + "\"");
  }

  // ---- POST ----

  @Test
  void send_requiresAuthentication() throws Exception {
    mvc.perform(
            post("/channels/3/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(KEY.toString(), "hola")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void send_newMessage_is201_withSeq() throws Exception {
    when(service.send(7L, 3L, new SendMessageRequest(KEY, "hola")))
        .thenReturn(new Sent(message(1), false));

    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(KEY.toString(), "hola")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.seq").value(1))
        .andExpect(jsonPath("$.clientMsgId").value(KEY.toString()))
        .andExpect(jsonPath("$.authorName").value("Ana"))
        .andExpect(jsonPath("$.createdAt").value("2026-10-26T15:00:00Z"));
  }

  @Test
  void send_retry_is200_withTheSameMessage() throws Exception {
    when(service.send(7L, 3L, new SendMessageRequest(KEY, "hola")))
        .thenReturn(new Sent(message(1), true));

    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(KEY.toString(), "hola")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seq").value(1));
  }

  @Test
  void send_withoutClientMsgId_is400() throws Exception {
    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(null, "hola")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.clientMsgId").exists());
    verifyNoInteractions(service);
  }

  @Test
  void send_blankBody_is400() throws Exception {
    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(KEY.toString(), "   ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fields.body").exists());
    verifyNoInteractions(service);
  }

  @Test
  void send_bodyOver4000Chars_is400() throws Exception {
    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(KEY.toString(), "x".repeat(4001))))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void send_clientMsgIdThatIsNotAUuid_is400() throws Exception {
    mvc.perform(
            post("/channels/3/messages")
                .with(jwt().jwt(j -> j.subject("7")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("no-soy-un-uuid", "hola")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  // ---- GET ----

  @Test
  void list_requiresAuthentication() throws Exception {
    mvc.perform(get("/channels/3/messages")).andExpect(status().isUnauthorized());
  }

  @Test
  void list_withoutParams_usesDefaultLimit() throws Exception {
    when(service.list(7L, 3L, null, null, 50))
        .thenReturn(new MessagePage(List.of(message(1), message(2)), false));

    mvc.perform(get("/channels/3/messages").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messages[0].seq").value(1))
        .andExpect(jsonPath("$.messages[1].seq").value(2))
        .andExpect(jsonPath("$.hasMore").value(false));
  }

  @Test
  void list_passesSnakeCaseCursorsAndLimit() throws Exception {
    when(service.list(7L, 3L, 4L, null, 10)).thenReturn(new MessagePage(List.of(), false));

    mvc.perform(
            get("/channels/3/messages?after_seq=4&limit=10").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk());
    verify(service).list(7L, 3L, 4L, null, 10);

    mvc.perform(get("/channels/3/messages?before_seq=9").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isOk());
    verify(service).list(7L, 3L, null, 9L, 50);
  }

  @Test
  void list_badRequestFromService_is400WithApiError() throws Exception {
    when(service.list(anyLong(), anyLong(), any(), any(), anyInt()))
        .thenThrow(new BadRequestException("Usa after_seq o before_seq, no los dos"));

    mvc.perform(
            get("/channels/3/messages?after_seq=1&before_seq=5")
                .with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));
  }

  @Test
  void list_nonNumericCursor_is400() throws Exception {
    mvc.perform(get("/channels/3/messages?after_seq=abc").with(jwt().jwt(j -> j.subject("7"))))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
