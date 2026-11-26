"use client";

import Link from "next/link";
import { useState } from "react";
import { ApiError, type Channel, type SearchResult, api } from "@/lib/api";
import { splitSnippet } from "@/lib/search";

/** Lo mismo que acepta el servidor: más largo es un 400. */
const MAX_QUERY_LENGTH = 200;

/** El fragmento con las palabras encontradas en `<mark>`, como elementos de React (nunca HTML). */
function Snippet({ text }: { text: string }) {
  return (
    <>
      {splitSnippet(text).map((p, i) => (p.hit ? <mark key={i}>{p.text}</mark> : p.text))}
    </>
  );
}

/**
 * Busca en los mensajes del servidor. El servidor busca solo en los canales que puedes leer
 * (ADR 0016), así que aquí no se filtra nada: lo que llega se puede mostrar. Los resultados van del
 * más nuevo al más viejo y enlazan al canal (saltar al mensaje queda para `docs/v2.md`).
 */
export function ServerSearch({
  serverId,
  channels,
}: {
  serverId: number;
  channels: Channel[];
}) {
  const [query, setQuery] = useState("");
  const [channelId, setChannelId] = useState<number | undefined>(undefined);
  /** La búsqueda que produjo los resultados de abajo: «Más resultados» sigue esa, no lo escrito. */
  const [searched, setSearched] = useState<{ q: string; channelId?: number } | null>(null);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(q: string, inChannel: number | undefined, beforeId?: number) {
    setLoading(true);
    try {
      const page = await api.search(serverId, { q, channelId: inChannel, beforeId });
      setResults((prev) => (beforeId === undefined ? page.results : [...prev, ...page.results]));
      setHasMore(page.hasMore);
      setSearched({ q, channelId: inChannel });
      setError(null);
    } catch (err) {
      setError(
        err instanceof ApiError && err.body.status < 500
          ? err.body.message
          : "No se pudo buscar",
      );
    } finally {
      setLoading(false);
    }
  }

  function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const q = query.trim();
    if (!q || loading) return;
    void run(q, channelId);
  }

  function loadMore() {
    if (!searched || results.length === 0) return;
    void run(searched.q, searched.channelId, results[results.length - 1].messageId);
  }

  const channelName = (id: number) => channels.find((c) => c.id === id)?.name;

  return (
    <section aria-label="Buscar">
      <h2>Buscar</h2>
      <form onSubmit={onSubmit} style={{ display: "flex", gap: "0.5rem" }}>
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar mensajes"
          aria-label="Buscar mensajes"
          maxLength={MAX_QUERY_LENGTH}
          style={{ flex: 1 }}
        />
        <select
          aria-label="Canal"
          value={channelId ?? ""}
          onChange={(e) => setChannelId(e.target.value ? Number(e.target.value) : undefined)}
        >
          <option value="">Todos los canales</option>
          {channels.map((c) => (
            <option key={c.id} value={c.id}>
              # {c.name}
            </option>
          ))}
        </select>
        <button type="submit" disabled={loading || !query.trim()}>
          {loading ? "Buscando…" : "Buscar"}
        </button>
      </form>
      {error && <p role="alert">{error}</p>}

      {searched && results.length === 0 && !loading && !error && (
        <p data-testid="search-empty">Sin resultados para «{searched.q}».</p>
      )}
      <ol data-testid="search-results" style={{ listStyle: "none", padding: 0 }}>
        {results.map((r) => (
          <li key={r.messageId} style={{ padding: "0.25rem 0" }}>
            <small style={{ opacity: 0.6 }}>
              <Link href={`/servers/${serverId}/channels/${r.channelId}`}>
                # {channelName(r.channelId) ?? r.channelName}
              </Link>{" "}
              · {new Date(r.createdAt).toLocaleString()}
            </small>{" "}
            <strong>{r.authorName}</strong>: <Snippet text={r.snippet} />
          </li>
        ))}
      </ol>
      {hasMore && (
        <button onClick={loadMore} disabled={loading}>
          {loading ? "Cargando…" : "Más resultados"}
        </button>
      )}
    </section>
  );
}
