"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError, api, setToken } from "@/lib/api";

export default function AuthForm({ mode }: { mode: "login" | "register" }) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const isRegister = mode === "register";

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const form = new FormData(e.currentTarget);
    const email = String(form.get("email"));
    const password = String(form.get("password"));
    try {
      const res = isRegister
        ? await api.register(email, password, String(form.get("displayName")))
        : await api.login(email, password);
      setToken(res.token);
      router.push("/");
    } catch (err) {
      if (err instanceof ApiError) {
        const first = Object.values(err.body.fields)[0];
        setError(first ?? err.body.message);
      } else {
        setError("No se pudo conectar con el servidor");
      }
    }
  }

  return (
    <main style={{ maxWidth: 360, margin: "4rem auto", padding: "0 1rem" }}>
      <h1>{isRegister ? "Crear cuenta" : "Entrar"}</h1>
      <form onSubmit={onSubmit} style={{ display: "grid", gap: "0.75rem" }}>
        {isRegister && (
          <input name="displayName" placeholder="Nombre" required maxLength={50} />
        )}
        <input name="email" type="email" placeholder="Correo" required />
        <input
          name="password"
          type="password"
          placeholder="Contraseña (mínimo 8)"
          required
          minLength={8}
        />
        <button type="submit">{isRegister ? "Registrarme" : "Entrar"}</button>
        {error && <p role="alert">{error}</p>}
      </form>
      <p>
        {isRegister ? (
          <Link href="/login">Ya tengo cuenta</Link>
        ) : (
          <Link href="/register">Crear cuenta</Link>
        )}
      </p>
    </main>
  );
}
