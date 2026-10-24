package com.jsav.chat.channel;

import com.jsav.chat.channel.ChannelDtos.AddChannelMemberRequest;
import com.jsav.chat.channel.ChannelDtos.ChannelResponse;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChannelController {

  private final ChannelService service;

  public ChannelController(ChannelService service) {
    this.service = service;
  }

  @GetMapping("/servers/{serverId}/channels")
  List<ChannelResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long serverId) {
    return service.list(Long.valueOf(jwt.getSubject()), serverId);
  }

  @PostMapping("/servers/{serverId}/channels")
  @ResponseStatus(HttpStatus.CREATED)
  ChannelResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long serverId,
      @Valid @RequestBody CreateChannelRequest req) {
    return service.create(Long.valueOf(jwt.getSubject()), serverId, req);
  }

  @GetMapping("/channels/{channelId}")
  ChannelResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long channelId) {
    return service.get(Long.valueOf(jwt.getSubject()), channelId);
  }

  @PostMapping("/channels/{channelId}/members")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void addMember(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long channelId,
      @Valid @RequestBody AddChannelMemberRequest req) {
    service.addMember(Long.valueOf(jwt.getSubject()), channelId, req.userId());
  }

  @DeleteMapping("/channels/{channelId}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void removeMember(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long channelId, @PathVariable Long userId) {
    service.removeMember(Long.valueOf(jwt.getSubject()), channelId, userId);
  }
}
