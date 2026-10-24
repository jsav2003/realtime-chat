"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, InvitePreview, api, clearToken, getToken, setNext } from "@/lib/api";

export default function InvitePage() {
  const router = useRouter();
  const { code } = useParams<{ code: string }>();

  const [preview, setPreview] = useState<InvitePreview | null>(null);
  const [invalid, setInvalid] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getToken()) {
      // Sin sesión: iniciar y volver aquí.
      setNext(`/invite/${code}`);
      router.replace("/login");
      return;
    }
    api
      .previewInvite(code)
      .then(setPreview)
      .catch((err) => {
        if (err instanceof ApiError && err.body.status === 401) {
          clearToken();
          setNext(`/invite/${code}`);
          router.replace("/login");
        } else if (err instanceof ApiError && err.body.status === 404) {
          setInvalid(true);
        } else {
          setError("No se pudo cargar la invitación");
        }
      });
  }, [code, router]);

  async function onJoin() {
    try {
      const server = await api.acceptInvite(code);
      router.push(`/servers/${server.id}`);
    } catch (err) {
      if (err instanceof ApiError && err.body.status === 404) {
        setInvalid(true);
      } else {
        setError("No se pudo unir al servidor");
      }
    }
  }

  return (
    <main style={{ maxWidth: 480, margin: "3rem auto", padding: "0 1rem" }}>
      {invalid ? (
        <>
          <p>Esta invitación no es válida, venció o ya se usó.</p>
          <Link href="/">Ir a mis servidores</Link>
        </>
      ) : preview ? (
        <>
          <h1>Te invitaron a {preview.serverName}</h1>
          <button onClick={onJoin}>Unirme</button>
        </>
      ) : (
        <p>Cargando…</p>
      )}
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
