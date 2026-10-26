package com.jsav.chat.message;

import com.jsav.chat.message.MessageDtos.MessagePage;
import com.jsav.chat.message.MessageDtos.MessageResponse;
import com.jsav.chat.message.MessageDtos.SendMessageRequest;
import com.jsav.chat.message.MessageService.Sent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {

  private final MessageService service;

  public MessageController(MessageService service) {
    this.service = service;
  }

  /** 201 si el mensaje es nuevo; 200 con el mismo mensaje si es un reintento. */
  @PostMapping("/channels/{channelId}/messages")
  ResponseEntity<MessageResponse> send(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long channelId,
      @Valid @RequestBody SendMessageRequest req) {
    Sent sent = service.send(Long.valueOf(jwt.getSubject()), channelId, req);
    HttpStatus status = sent.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(sent.message());
  }

  @GetMapping("/channels/{channelId}/messages")
  MessagePage list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long channelId,
      @RequestParam(name = "after_seq", required = false) Long afterSeq,
      @RequestParam(name = "before_seq", required = false) Long beforeSeq,
      @RequestParam(defaultValue = "" + MessageService.DEFAULT_LIMIT) int limit) {
    return service.list(Long.valueOf(jwt.getSubject()), channelId, afterSeq, beforeSeq, limit);
  }
}
