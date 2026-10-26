"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, Channel, Message, api, clearToken, getToken } from "@/lib/api";
import { mergeBySeq } from "@/lib/messages";

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

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    Promise.all([api.getChannel(channelId), api.listMessages(channelId)])
      .then(([c, page]) => {
        setChannel(c);
        setMessages(page.messages);
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
  }, [router, channelId]);

  /** Historial hacia atrás: lo anterior al primer seq que ya se tiene. */
  async function loadOlder() {
    if (messages.length === 0) return;
    setLoadingOlder(true);
    try {
      const page = await api.listMessages(channelId, { beforeSeq: messages[0].seq });
      setMessages((prev) => mergeBySeq(prev, page.messages));
      setHasOlder(page.hasMore);
      setError(null);
    } catch {
      setError("No se pudieron cargar los mensajes anteriores");
    } finally {
      setLoadingOlder(false);
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
    </main>
  );
}
