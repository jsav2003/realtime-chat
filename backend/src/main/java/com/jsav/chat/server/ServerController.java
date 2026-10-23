package com.jsav.chat.server;

import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerDtos.ServerResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/servers")
public class ServerController {

  private final ServerService service;

  public ServerController(ServerService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ServerResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateServerRequest req) {
    return service.create(Long.valueOf(jwt.getSubject()), req);
  }

  @GetMapping
  List<ServerResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
    return service.listMine(Long.valueOf(jwt.getSubject()));
  }
}
