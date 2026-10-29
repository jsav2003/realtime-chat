package com.jsav.chat.typing;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TypingController {

  private final TypingService service;

  public TypingController(TypingService service) {
    this.service = service;
  }

  /** 204 siempre que el usuario pueda escribir en el canal, se haya publicado o no. */
  @PostMapping("/channels/{channelId}/typing")
  ResponseEntity<Void> typing(@AuthenticationPrincipal Jwt jwt, @PathVariable Long channelId) {
    service.typing(Long.valueOf(jwt.getSubject()), channelId);
    return ResponseEntity.noContent().build();
  }
}
