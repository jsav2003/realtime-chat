package com.jsav.chat.notification;

import com.jsav.chat.notification.NotificationDtos.NotificationList;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Las notificaciones son siempre del usuario autenticado: ningún endpoint recibe el id de otro. */
@RestController
public class NotificationController {

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping("/notifications")
  NotificationList list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "" + NotificationService.DEFAULT_LIMIT) int limit) {
    return service.list(Long.valueOf(jwt.getSubject()), limit);
  }

  /** 204, o 404 si esa notificación no existe o no es tuya. */
  @PutMapping("/notifications/{notificationId}/read")
  ResponseEntity<Void> markRead(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long notificationId) {
    service.markRead(Long.valueOf(jwt.getSubject()), notificationId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/notifications/read-all")
  ResponseEntity<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
    service.markAllRead(Long.valueOf(jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }
}
