package com.jsav.chat.search;

import java.time.Instant;
import java.util.List;

public final class SearchDtos {

  private SearchDtos() {}

  /**
   * Un mensaje que coincide. {@code snippet} es un fragmento del mensaje con cada palabra
   * encontrada entre {@link SearchRepository#MARK_START} y {@link SearchRepository#MARK_END}; no es
   * HTML, y esos dos caracteres nunca vienen del texto que escribió el autor.
   */
  public record SearchResult(
      long messageId,
      long channelId,
      String channelName,
      long seq,
      String authorName,
      Instant createdAt,
      String snippet) {}

  /**
   * Los más nuevos primero. Para la página siguiente se pide con {@code before_id} igual al último
   * {@code messageId}.
   */
  public record SearchPage(List<SearchResult> results, boolean hasMore) {}
}
