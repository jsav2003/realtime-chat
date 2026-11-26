"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  ApiError,
  Channel,
  Invite,
  ServerDetail,
  api,
  clearToken,
  getToken,
} from "@/lib/api";
import { MemberList } from "./members";

/**
 * Cada cuánto se vuelven a pedir los no leídos. Como la presencia, es por consulta y no por aviso:
 * el contador sube con a lo sumo este retraso.
 */
const UNREAD_POLL_MS = 20_000;

/** Lo que se muestra junto al canal: nada, `(3)` o `(99+)`. */
function unreadLabel(unread: number): string {
  if (unread <= 0) return "";
  return unread > 99 ? "(99+)" : `(${unread})`;
}

/**
 * Los no leídos de cada canal, por id. `null` mientras no se sabe (o si falló): entonces no se
 * pinta ningún contador, porque un cero afirmaría que todo está leído.
 */
function useUnread(serverId: number, enabled: boolean): Map<number, number> | null {
  const [unread, setUnread] = useState<Map<number, number> | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;

    function load() {
      api
        .getUnread(serverId)
        .then((u) => {
          if (!cancelled) setUnread(new Map(u.channels.map((c) => [c.channelId, c.unread])));
        })
        .catch(() => {
          if (!cancelled) setUnread(null);
        });
    }

    load();
    const timer = setInterval(load, UNREAD_POLL_MS);
    // Al volver a la pestaña (o del canal con el botón «atrás»), se pide en el acto.
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [serverId, enabled]);

  return unread;
}

export default function ServerPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const serverId = Number(params.id);

  const [server, setServer] = useState<ServerDetail | null>(null);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [invite, setInvite] = useState<Invite | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const unread = useUnread(serverId, server !== null);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    Promise.all([api.getServer(serverId), api.listChannels(serverId)])
      .then(([s, c]) => {
        setServer(s);
        setChannels(c);
      })
      .catch((err) => {
        if (err instanceof ApiError && err.body.status === 401) {
          clearToken();
          router.replace("/login");
        } else if (err instanceof ApiError && err.body.status === 404) {
          setNotFound(true);
        } else {
          setError("No se pudo cargar el servidor");
        }
      });
  }, [router, serverId]);

  async function onCreateChannel(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    try {
      const created = await api.createChannel(
        serverId,
        String(data.get("name")),
        data.get("kind") === "private" ? "private" : "text",
      );
      setChannels((prev) => [...prev, created]);
      form.reset();
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.body.message : "No se pudo crear el canal");
    }
  }

  async function onCreateInvite() {
    try {
      setInvite(await api.createInvite(serverId));
      setError(null);
    } catch (err) {
      setError(
        err instanceof ApiError ? err.body.message : "No se pudo crear la invitación",
      );
    }
  }

  if (notFound) {
    return (
      <main style={{ maxWidth: 480, margin: "3rem auto", padding: "0 1rem" }}>
        <p>Servidor no encontrado.</p>
        <Link href="/">Volver a mis servidores</Link>
      </main>
    );
  }

  const canAdmin = server?.myRole === "owner" || server?.myRole === "admin";

  return (
    <main style={{ maxWidth: 480, margin: "3rem auto", padding: "0 1rem" }}>
      <p>
        <Link href="/">← Mis servidores</Link>
      </p>
      <h1>{server?.name ?? "Cargando…"}</h1>
      {error && <p role="alert">{error}</p>}

      <h2>Canales</h2>
      <ul>
        {channels.map((c) => {
          const count = unread?.get(c.id) ?? 0;
          return (
            <li key={c.id} data-testid={`channel-${c.id}`} data-unread={count}>
              <Link
                href={`/servers/${serverId}/channels/${c.id}`}
                style={{ fontWeight: count > 0 ? "bold" : undefined }}
              >
                # {c.name}
              </Link>{" "}
              {count > 0 && (
                <strong aria-label={`${count} sin leer`}>{unreadLabel(count)}</strong>
              )}{" "}
              {c.kind === "private" && <small>(privado)</small>}
            </li>
          );
        })}
      </ul>

      {server && <MemberList serverId={serverId} />}

      {canAdmin && (
        <>
          <form onSubmit={onCreateChannel} style={{ display: "flex", gap: "0.5rem" }}>
            <input name="name" placeholder="Nombre del canal" required maxLength={100} />
            <select name="kind" defaultValue="text">
              <option value="text">Público</option>
              <option value="private">Privado</option>
            </select>
            <button type="submit">Crear canal</button>
          </form>

          <h2>Invitar</h2>
          <button onClick={onCreateInvite}>Crear invitación</button>
          {invite && (
            <p>
              Link: <code>{`${window.location.origin}${invite.path}`}</code>
            </p>
          )}
        </>
      )}
    </main>
  );
}
