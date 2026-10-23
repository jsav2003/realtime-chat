"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ApiError, Server, api, clearToken, getToken } from "@/lib/api";

export default function Home() {
  const router = useRouter();
  const [servers, setServers] = useState<Server[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    api
      .listServers()
      .then(setServers)
      .catch((err) => {
        if (err instanceof ApiError && err.body.status === 401) {
          clearToken();
          router.replace("/login");
        } else {
          setError("No se pudieron cargar los servidores");
        }
      });
  }, [router]);

  async function onCreate(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const name = String(new FormData(form).get("name"));
    try {
      const created = await api.createServer(name);
      setServers((prev) => [...(prev ?? []), created]);
      form.reset();
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.body.message : "No se pudo crear");
    }
  }

  function logout() {
    clearToken();
    router.replace("/login");
  }

  return (
    <main style={{ maxWidth: 480, margin: "3rem auto", padding: "0 1rem" }}>
      <header style={{ display: "flex", justifyContent: "space-between" }}>
        <h1>Mis servidores</h1>
        <button onClick={logout}>Salir</button>
      </header>
      <form onSubmit={onCreate} style={{ display: "flex", gap: "0.5rem" }}>
        <input name="name" placeholder="Nombre del servidor" required maxLength={100} />
        <button type="submit">Crear</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {servers === null ? (
        <p>Cargando…</p>
      ) : servers.length === 0 ? (
        <p>Todavía no estás en ningún servidor.</p>
      ) : (
        <ul>
          {servers.map((s) => (
            <li key={s.id}>{s.name}</li>
          ))}
        </ul>
      )}
    </main>
  );
}
