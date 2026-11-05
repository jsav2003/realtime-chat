package com.jsav.chat.readstate;

import com.jsav.chat.readstate.ReadStateDtos.MarkReadRequest;
import com.jsav.chat.readstate.ReadStateDtos.UnreadList;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadStateController {

  private final ReadStateService service;

  public ReadStateController(ReadStateService service) {
    this.service = service;
  }

  /** Los no leídos de los canales del servidor que puedes leer; 404 si no eres miembro. */
  @GetMapping("/servers/{serverId}/unread")
  UnreadList unread(@AuthenticationPrincipal Jwt jwt, @PathVariable Long serverId) {
    return service.unread(Long.valueOf(jwt.getSubject()), serverId);
  }

  /** 204. Idempotente: repetirlo, o mandarlo con un seq menor, no cambia nada. */
  @PutMapping("/channels/{channelId}/read")
  ResponseEntity<Void> markRead(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long channelId,
      @Valid @RequestBody MarkReadRequest req) {
    service.markRead(Long.valueOf(jwt.getSubject()), channelId, req.seq());
    return ResponseEntity.noContent().build();
  }
}
