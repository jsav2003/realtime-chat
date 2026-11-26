package com.jsav.chat.search;

import com.jsav.chat.search.SearchDtos.SearchPage;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

  private final SearchService service;

  public SearchController(SearchService service) {
    this.service = service;
  }

  /**
   * Los mensajes que coinciden con {@code q} en los canales del servidor que puedes leer, del más
   * nuevo al más viejo. 404 si no eres miembro, o si {@code channel_id} no es un canal que ves en
   * este servidor; 400 si {@code q} está vacío o es muy largo.
   */
  @GetMapping("/servers/{serverId}/search")
  SearchPage search(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long serverId,
      @RequestParam(required = false) String q,
      @RequestParam(name = "channel_id", required = false) Long channelId,
      @RequestParam(name = "before_id", required = false) Long beforeId,
      @RequestParam(defaultValue = "" + SearchService.DEFAULT_LIMIT) int limit) {
    return service.search(Long.valueOf(jwt.getSubject()), serverId, q, channelId, beforeId, limit);
  }
}
