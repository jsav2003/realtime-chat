"use client";

import { useEffect, useState } from "react";
import { type Member, api, getUserId } from "@/lib/api";

/**
 * Cada cuánto se vuelve a preguntar quién está en línea. La presencia es por consulta y no por
 * aviso (ADR 0008): el cambio se ve con a lo sumo este retraso.
 */
const PRESENCE_POLL_MS = 20_000;

/**
 * Los miembros del servidor, con un punto verde si tienen alguna pestaña con el WebSocket abierto
 * (en cualquier instancia del backend). Ojo: el socket solo se abre en la página de un canal, así
 * que alguien que está mirando la lista de canales aparece sin conexión; tú siempre apareces en
 * línea, porque estás mirando esta pantalla.
 */
export function MemberList({ serverId }: { serverId: number }) {
  const [members, setMembers] = useState<Member[]>([]);
  /**
   * `null` si no se pudo saber (por ejemplo, Redis caído y el servidor responde 503): entonces no
   * se pintan puntos, porque un punto gris afirmaría que todos están desconectados.
   */
  const [online, setOnline] = useState<Set<number> | null>(null);
  const me = getUserId();

  useEffect(() => {
    api
      .listMembers(serverId)
      .then(setMembers)
      .catch(() => setMembers([]));
  }, [serverId]);

  useEffect(() => {
    let cancelled = false;

    function load() {
      api
        .getPresence(serverId)
        .then((p) => {
          if (!cancelled) setOnline(new Set(p.online));
        })
        .catch(() => {
          if (!cancelled) setOnline(null);
        });
    }

    load();
    const timer = setInterval(load, PRESENCE_POLL_MS);
    // Al volver a la pestaña, el dato puede tener minutos: se pide en el acto.
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      cancelled = true;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [serverId]);

  const isOnline = (m: Member) => m.userId === me || (online?.has(m.userId) ?? false);
  const sorted = [...members].sort(
    (a, b) =>
      Number(isOnline(b)) - Number(isOnline(a)) ||
      a.displayName.localeCompare(b.displayName),
  );

  return (
    <section aria-label="Miembros">
      <h2>Miembros</h2>
      <ul style={{ listStyle: "none", padding: 0 }}>
        {sorted.map((m) => (
          <li
            key={m.userId}
            data-testid={`member-${m.userId}`}
            data-online={online === null ? "unknown" : String(isOnline(m))}
          >
            {online !== null && (
              <span
                role="img"
                aria-label={isOnline(m) ? "en línea" : "sin conexión"}
                style={{ color: isOnline(m) ? "#2a9d4b" : "#999" }}
              >
                ●
              </span>
            )}{" "}
            {m.displayName}
            {m.userId === me && " (tú)"}{" "}
            <small style={{ opacity: 0.6 }}>({m.role})</small>
          </li>
        ))}
      </ul>
    </section>
  );
}
