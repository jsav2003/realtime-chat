package com.jsav.chat.presence;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PresenceController {

  /** Los ids de los miembros que están en línea. */
  public record PresenceResponse(List<Long> online) {}

  private final PresenceService service;

  public PresenceController(PresenceService service) {
    this.service = service;
  }

  @GetMapping("/servers/{serverId}/presence")
  PresenceResponse presence(@AuthenticationPrincipal Jwt jwt, @PathVariable Long serverId) {
    return new PresenceResponse(service.online(Long.valueOf(jwt.getSubject()), serverId));
  }
}
