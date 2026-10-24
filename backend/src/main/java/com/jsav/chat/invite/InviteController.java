package com.jsav.chat.invite;

import com.jsav.chat.invite.InviteDtos.CreateInviteRequest;
import com.jsav.chat.invite.InviteDtos.InvitePreviewResponse;
import com.jsav.chat.invite.InviteDtos.InviteResponse;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InviteController {

  private final InviteService service;

  public InviteController(InviteService service) {
    this.service = service;
  }

  @PostMapping("/servers/{serverId}/invites")
  @ResponseStatus(HttpStatus.CREATED)
  InviteResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long serverId,
      @Valid @RequestBody(required = false) CreateInviteRequest req) {
    CreateInviteRequest body = req == null ? new CreateInviteRequest(null, null) : req;
    return service.create(Long.valueOf(jwt.getSubject()), serverId, body);
  }

  @GetMapping("/invites/{code}")
  InvitePreviewResponse preview(@PathVariable String code) {
    return service.preview(code);
  }

  @PostMapping("/invites/{code}/accept")
  ServerResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
    return service.accept(Long.valueOf(jwt.getSubject()), code);
  }
}
