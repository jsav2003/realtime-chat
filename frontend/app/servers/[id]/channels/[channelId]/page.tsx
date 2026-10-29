"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  Channel,
  Message,
  api,
  clearToken,
  getToken,
  getUserId,
} from "@/lib/api";
import { contiguousSeqOf, lastSeqOf, mergeBySeq, sendWithRetry } from "@/lib/messages";
import { type ConnectionState, openChannelSocket } from "@/lib/realtime";
import { MemberList } from "../../members";

const CONNECTION_LABELS: Record<ConnectionState, string> = {
  connecting: "Conectando…",
  live: "● En vivo",
  reconnecting:
    "Sin conexión en vivo; reconectando… (puedes seguir usando «Buscar nuevos»)",
  closed: "Sin conexión en vivo",
};

/** Cuánto dura el «escribiendo…» sin un aviso nuevo (el compositor avisa cada ~2 s). */
const TYPING_VISIBLE_MS = 4000;

/** Mínimo entre avisos de «escribiendo…» al servidor; él también los frena. */
const TYPING_NOTICE_EVERY_MS = 2000;

type Typers = Record<number, { name: string; at: number }>;

/** «Ana está escribiendo…», «Ana y Beto están escribiendo…» o «Varias personas…». */
function typingText(names: string[]): string {
  if (names.length === 0) return "";
  if (names.length === 1) return `${names[0]} está escribiendo…`;
  if (names.length === 2) return `${names[0]} y ${names[1]} están escribiendo…`;
  return "Varias personas están escribiendo…";
}

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
  const [connection, setConnection] = useState<ConnectionState>("connecting");
  /** Quién está escribiendo ahora, con la hora del último aviso (ver `TYPING_VISIBLE_MS`). */
  const [typers, setTypers] = useState<Typers>({});
  /** Segundos que el servidor pidió esperar (429); `null` si no se está esperando. */
  const [rateLimitWait, setRateLimitWait] = useState<number | null>(null);
  const lastTypingNoticeAt = useRef(0);
  /**
   * Los mensajes que se tienen, siempre al día. Los eventos del WebSocket llegan fuera del render
   * y no pueden leer el estado de React sin quedarse con un valor viejo; la referencia sí.
   */
  const messagesRef = useRef<Message[]>([]);

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
        const known = contiguousSeqOf(messagesRef.current);
        addMessages([m]);
        if (m.seq > known + 1) void resync(known);
        // Quien acaba de enviar un mensaje ya no está «escribiendo».
        setTypers((prev) => {
          if (!(m.authorId in prev)) return prev;
          const rest = { ...prev };
          delete rest[m.authorId];
          return rest;
        });
      },
      onTyping: (t) => {
        // El propio aviso no se muestra; el servidor no lo filtra porque es solo interfaz.
        if (t.userId === getUserId()) return;
        setTypers((prev) => ({
          ...prev,
          [t.userId]: { name: t.displayName, at: Date.now() },
        }));
      },
      // Primera vez y después de cada reconexión: pedir lo que se pudo perder mientras no se
      // estaba suscrito. Se hace recién con el RECEIPT, porque antes no hay garantía de estar
      // recibiendo y quedaría un hueco entre este pedido y lo vivo.
      onSubscribed: () => void resync(contiguousSeqOf(messagesRef.current)),
      onState: setConnection,
      // El servidor ya no deja suscribirse. 401: el token venció. 404: el canal ya no se ve,
      // por ejemplo porque expulsaron al usuario (se le cerró el socket y al reconectar se
      // le negó la suscripción). Reintentar no cambiaría nada.
      onRejected: (status) => {
        if (status === 401) {
          clearToken();
          router.replace("/login");
        } else {
          setNotFound(true);
        }
      },
    });
  }, [channel, channelId, addMessages, resync, router]);

  /** Quita de «escribiendo…» a quien lleva un rato sin avisar (cada segundo). */
  useEffect(() => {
    const timer = setInterval(() => {
      setTypers((prev) => {
        const now = Date.now();
        const alive = Object.entries(prev).filter(
          ([, t]) => now - t.at < TYPING_VISIBLE_MS,
        );
        return alive.length === Object.keys(prev).length
          ? prev
          : Object.fromEntries(alive);
      });
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  /**
   * Avisa que el usuario está escribiendo, como mucho una vez cada `TYPING_NOTICE_EVERY_MS`. Si
   * falla no pasa nada: es un adorno, y el siguiente tecleo lo vuelve a intentar.
   */
  function noticeTyping() {
    const now = Date.now();
    if (now - lastTypingNoticeAt.current < TYPING_NOTICE_EVERY_MS) return;
    lastTypingNoticeAt.current = now;
    api.sendTyping(channelId).catch(() => {});
  }

  async function onSend(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const body = draft.trim();
    if (!body || sending) return;
    const key = failedKey ?? crypto.randomUUID();
    const known = contiguousSeqOf(messagesRef.current);
    setSending(true);
    try {
      const sent = await sendWithRetry(channelId, key, body, setRateLimitWait);
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
      setRateLimitWait(null);
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
      <p aria-live="polite" style={{ margin: 0, opacity: 0.7 }}>
        <small data-testid="connection-status" data-state={connection}>
          {CONNECTION_LABELS[connection]}
        </small>
      </p>
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
          onClick={() => resync(contiguousSeqOf(messages))}
          disabled={!channel || syncing}
        >
          {syncing ? "Buscando…" : "Buscar nuevos"}
        </button>
      </p>

      <p
        aria-live="polite"
        data-testid="typing-indicator"
        style={{ margin: "0 0 0.25rem", minHeight: "1.25rem", opacity: 0.7 }}
      >
        <small>{typingText(Object.values(typers).map((t) => t.name))}</small>
      </p>
      {rateLimitWait !== null && (
        <p role="status" data-testid="rate-limit-notice">
          Vas muy rápido: el mensaje se enviará en unos {rateLimitWait} s.
        </p>
      )}

      <form onSubmit={onSend} style={{ display: "flex", gap: "0.5rem" }}>
        <input
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            setFailedKey(null);
            if (e.target.value.trim()) noticeTyping();
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

      {channel && <MemberList serverId={serverId} />}
    </main>
  );
}
