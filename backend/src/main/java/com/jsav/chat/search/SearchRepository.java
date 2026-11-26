package com.jsav.chat.search;

import com.jsav.chat.search.SearchDtos.SearchResult;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La búsqueda de texto completo sobre {@code messages.search} (migración V10, ADR 0016), con SQL
 * directo como el resto del camino de los mensajes.
 */
@Repository
public class SearchRepository {

  /** Abre una palabra encontrada en el fragmento. Uso privado de Unicode: no es texto normal. */
  public static final String MARK_START = "";

  /** Cierra una palabra encontrada en el fragmento. */
  public static final String MARK_END = "";

  /**
   * Opciones de {@code ts_headline}: los dos marcadores, y un fragmento de unas 10 a 25 palabras
   * alrededor de lo encontrado, en vez del mensaje entero.
   */
  private static final String HEADLINE_OPTIONS =
      "StartSel=" + MARK_START + ", StopSel=" + MARK_END + ", MinWords=10, MaxWords=25";

  private final JdbcClient jdbc;

  public SearchRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Hasta {@code limit} mensajes de estos canales que coinciden con {@code query}, con {@code id <
   * beforeId}, del más nuevo al más viejo.
   *
   * <ul>
   *   <li><b>El filtro de canales va en el {@code WHERE}, antes del {@code LIMIT}.</b> Si se
   *       filtrara después, una página podría salir corta (o vacía) porque lo más nuevo estaba en
   *       un canal oculto, y eso revelaría que ahí hay resultados.
   *   <li>{@code websearch_to_tsquery} acepta cualquier entrada (comillas, {@code -}, {@code or})
   *       sin error de sintaxis. Si solo hay palabras vacías («de la») la consulta queda vacía y no
   *       coincide con nada.
   *   <li>{@code ts_headline} es caro (vuelve a analizar el texto), así que se aplica afuera, solo
   *       a las filas de la página. Antes se quitan del cuerpo los dos marcadores: si el autor los
   *       escribió, no pueden fingir un resaltado.
   * </ul>
   *
   * <p>Quién puede leer cada canal lo decide quien llama; aquí no hay ninguna regla de acceso.
   */
  public List<SearchResult> search(
      Collection<Long> channelIds, String query, long beforeId, int limit) {
    if (channelIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            select p.id, p.channel_id, c.name as channel_name, p.seq,
                   u.display_name as author_name, p.created_at,
                   ts_headline('spanish', translate(p.body, :marks, ''),
                               websearch_to_tsquery('spanish', :query), :options) as snippet
            from (
              select m.id, m.channel_id, m.seq, m.author_id, m.body, m.created_at
              from messages m
              where m.channel_id in (:channelIds)
                and m.search @@ websearch_to_tsquery('spanish', :query)
                and m.id < :beforeId
              order by m.id desc
              limit :limit
            ) p
            join channels c on c.id = p.channel_id
            join users u on u.id = p.author_id
            order by p.id desc
            """)
        .param("channelIds", channelIds)
        .param("query", query)
        .param("beforeId", beforeId)
        .param("limit", limit)
        .param("marks", MARK_START + MARK_END)
        .param("options", HEADLINE_OPTIONS)
        .query(
            (rs, i) ->
                new SearchResult(
                    rs.getLong("id"),
                    rs.getLong("channel_id"),
                    rs.getString("channel_name"),
                    rs.getLong("seq"),
                    rs.getString("author_name"),
                    rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getString("snippet")))
        .list();
  }
}
