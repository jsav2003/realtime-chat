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

export default function ServerPage() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const serverId = Number(params.id);

  const [server, setServer] = useState<ServerDetail | null>(null);
  const [channels, setChannels] = useState<Channel[]>([]);
  const [invite, setInvite] = useState<Invite | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

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
        {channels.map((c) => (
          <li key={c.id}>
            <Link href={`/servers/${serverId}/channels/${c.id}`}># {c.name}</Link>{" "}
            {c.kind === "private" && <small>(privado)</small>}
          </li>
        ))}
      </ul>

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
