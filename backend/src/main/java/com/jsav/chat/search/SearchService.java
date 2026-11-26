package com.jsav.chat.search;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.channel.Channel;
import com.jsav.chat.common.BadRequestException;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.search.SearchDtos.SearchPage;
import com.jsav.chat.search.SearchDtos.SearchResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Búsqueda en los mensajes de un servidor (ADR 0016). Nunca devuelve un mensaje de un canal que la
 * persona no puede leer: los canales salen de {@link AccessPolicy#readableChannels}, la misma regla
 * que la lista de canales y los no leídos, y se filtran en la consulta, antes del límite.
 */
@Service
public class SearchService {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 50;
  public static final int MAX_QUERY_LENGTH = 200;

  private final SearchRepository search;
  private final AccessPolicy policy;

  public SearchService(SearchRepository search, AccessPolicy policy) {
    this.search = search;
    this.policy = policy;
  }

  /**
   * @param channelId solo en ese canal, o {@code null} para todo el servidor. Si no es un canal que
   *     la persona puede leer en este servidor, 404 (igual que pedir sus mensajes).
   * @param beforeId cursor: solo mensajes con id menor, o {@code null} para empezar.
   */
  @Transactional(readOnly = true)
  public SearchPage search(
      Long userId, Long serverId, String q, Long channelId, Long beforeId, int limit) {
    String query = q == null ? "" : q.strip();
    if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
      throw new BadRequestException("q debe tener entre 1 y " + MAX_QUERY_LENGTH + " caracteres");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit debe estar entre 1 y " + MAX_LIMIT);
    }
    if (beforeId != null && beforeId < 1) {
      throw new BadRequestException("before_id debe ser >= 1");
    }

    // 404 a extraños y expulsados antes de mirar nada más.
    List<Long> visible =
        policy.readableChannels(userId, serverId).stream().map(Channel::getId).toList();
    if (channelId != null) {
      // Uno de otro servidor, un privado ajeno o uno que no existe: los tres son «no lo ves».
      if (!visible.contains(channelId)) {
        throw new NotFoundException("Canal no encontrado");
      }
      visible = List.of(channelId);
    }

    List<SearchResult> rows =
        search.search(visible, query, beforeId == null ? Long.MAX_VALUE : beforeId, limit + 1);
    boolean hasMore = rows.size() > limit;
    return new SearchPage(hasMore ? rows.subList(0, limit) : rows, hasMore);
  }
}
