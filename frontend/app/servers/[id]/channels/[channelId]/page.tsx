"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, Channel, Message, api, clearToken, getToken } from "@/lib/api";
import { lastSeqOf, mergeBySeq, sendWithRetry } from "@/lib/messages";
import { openChannelSocket } from "@/lib/realtime";

export default function ChannelPage() {
  const router = useRouter();
  const params = useParams<{ id: string; channelId: string }>();
  const serverId = Number(params.id);
  const channelId = Number(params.channelId);

  const [channel, setChannel] = useState<Channel | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [hasOlder, setHasOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  /**
   * Clave del envío que falló. Si el usuario vuelve a enviar el mismo texto se reutiliza, por si
   * el intento anterior sí alcanzó a guardarse; si cambia el texto, es otro mensaje y otra clave.
   */
  const [failedKey, setFailedKey] = useState<string | null>(null);
  const [syncing, setSyncing] = useState(false);
  /**
   * Los mensajes que se tienen, siempre al día. Los eventos del WebSocket llegan fuera del render
   * y no pueden leer el estado de React sin quedarse con un valor viejo; la referencia sí.
   */
  const messagesRef = useRef<Message[]>([]);
  /** Ya se cargó el historial: antes de eso un seq "adelantado" no es un hueco. */
  const loadedRef = useRef(false);

  /** Une mensajes a los que ya se tienen (descarta repetidos y ordena por seq). */
  const addMessages = useCallback((incoming: Message[]) => {
    messagesRef.current = mergeBySeq(messagesRef.current, incoming);
    setMessages(messagesRef.current);
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    Promise.all([api.getChannel(channelId), api.listMessages(channelId)])
      .then(([c, page]) => {
        setChannel(c);
        addMessages(page.messages);
        loadedRef.current = true;
        setHasOlder(page.hasMore);
      })
      .catch((err) => {
        if (err instanceof ApiError && err.body.status === 401) {
          clearToken();
          router.replace("/login");
        } else if (err instanceof ApiError && err.body.status === 404) {
          setNotFound(true);
        } else {
          setError("No se pudo cargar el canal");
        }
      });
  }, [router, channelId, addMessages]);

  /** Historial hacia atrás: lo anterior al primer seq que ya se tiene. */
  async function loadOlder() {
    if (messages.length === 0) return;
    setLoadingOlder(true);
    try {
      const page = await api.listMessages(channelId, { beforeSeq: messages[0].seq });
      addMessages(page.messages);
      setHasOlder(page.hasMore);
      setError(null);
    } catch {
      setError("No se pudieron cargar los mensajes anteriores");
    } finally {
      setLoadingOlder(false);
    }
  }

  /**
   * Resincronización (DESIGN.md §3.2): pide todo lo posterior al último seq que se tiene, página
   * por página. Hasta el hito 4 es la única forma de ver lo que escribieron los demás.
   */
  const resync = useCallback(
    async (afterSeq: number) => {
      setSyncing(true);
      try {
        const fetched: Message[] = [];
        let after = afterSeq;
        for (;;) {
          const page = await api.listMessages(channelId, { afterSeq: after });
          fetched.push(...page.messages);
          if (!page.hasMore || page.messages.length === 0) break;
          after = lastSeqOf(page.messages);
        }
        addMessages(fetched);
        setError(null);
      } catch {
        setError("No se pudieron buscar mensajes nuevos");
      } finally {
        setSyncing(false);
      }
    },
    [channelId, addMessages],
  );

  /**
   * Tiempo real: el canal se abre solo después de cargar el canal (si no se puede ver, ya
   * habrá fallado). Un mensaje en vivo se une por seq, así que uno repetido no hace nada, y si
   * el seq salta, se pide lo que falta por HTTP (DESIGN.md §3.2).
   */
  useEffect(() => {
    if (!channel) return;
    return openChannelSocket(channelId, {
      onMessage: (m) => {
        const known = lastSeqOf(messagesRef.current);
        addMessages([m]);
        if (loadedRef.current && m.seq > known + 1) void resync(known);
      },
      onSubscribed: () => {},
      onState: () => {},
      onRejected: () => {},
    });
  }, [channel, channelId, addMessages, resync]);

  async function onSend(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const body = draft.trim();
    if (!body || sending) return;
    const key = failedKey ?? crypto.randomUUID();
    const known = lastSeqOf(messagesRef.current);
    setSending(true);
    try {
      const sent = await sendWithRetry(channelId, key, body);
      addMessages([sent]);
      setDraft("");
      setFailedKey(null);
      setError(null);
      // Hueco: esperaba known + 1 y llegó algo mayor, así que otros escribieron entre medio.
      // Se pide lo que falta en vez de mostrar el canal incompleto.
      if (sent.seq > known + 1) void resync(known);
    } catch (err) {
      setFailedKey(key);
      setError(
        err instanceof ApiError && err.body.status < 500
          ? err.body.message
          : "No se pudo enviar. Vuelve a intentarlo: no se duplicará.",
      );
    } finally {
      setSending(false);
    }
  }

  if (notFound) {
    return (
      <main style={{ maxWidth: 640, margin: "3rem auto", padding: "0 1rem" }}>
        <p>Canal no encontrado.</p>
        <Link href={`/servers/${serverId}`}>Volver al servidor</Link>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 640, margin: "3rem auto", padding: "0 1rem" }}>
      <p>
        <Link href={`/servers/${serverId}`}>← Volver al servidor</Link>
      </p>
      <h1>
        # {channel?.name ?? "Cargando…"}{" "}
        {channel?.kind === "private" && <small>(privado)</small>}
      </h1>
      {error && <p role="alert">{error}</p>}

      {hasOlder && (
        <button onClick={loadOlder} disabled={loadingOlder}>
          {loadingOlder ? "Cargando…" : "Cargar anteriores"}
        </button>
      )}

      {channel && messages.length === 0 && <p>Todavía no hay mensajes.</p>}
      <ol style={{ listStyle: "none", padding: 0 }}>
        {messages.map((m) => (
          <li key={m.seq} style={{ padding: "0.25rem 0" }}>
            <small style={{ opacity: 0.6 }}>
              #{m.seq} · {new Date(m.createdAt).toLocaleTimeString()}
            </small>{" "}
            <strong>{m.authorName}</strong>: {m.body}
          </li>
        ))}
      </ol>

      <p>
        <button
          onClick={() => resync(lastSeqOf(messages))}
          disabled={!channel || syncing}
        >
          {syncing ? "Buscando…" : "Buscar nuevos"}
        </button>
      </p>

      <form onSubmit={onSend} style={{ display: "flex", gap: "0.5rem" }}>
        <input
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            setFailedKey(null);
          }}
          placeholder={`Escribe en #${channel?.name ?? ""}`}
          maxLength={4000}
          disabled={!channel}
          style={{ flex: 1 }}
        />
        <button type="submit" disabled={!channel || sending || !draft.trim()}>
          {sending ? "Enviando…" : "Enviar"}
        </button>
      </form>
    </main>
  );
}
