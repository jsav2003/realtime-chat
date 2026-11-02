"use client";

import Link from "next/link";
import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { AUTH_EVENT, type NotificationList, api, getToken } from "@/lib/api";

/**
 * Cada cuánto se vuelve a preguntar por menciones nuevas. Como la presencia (ADR 0008), las
 * notificaciones se consultan en vez de recibirse por aviso: se ven con a lo sumo este retraso.
 */
const NOTIFICATIONS_POLL_MS = 20_000;

/** Avisa cuando la sesión cambia: aquí mismo (`AUTH_EVENT`) o en otra pestaña (`storage`). */
function subscribeToSession(onChange: () => void) {
  window.addEventListener(AUTH_EVENT, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(AUTH_EVENT, onChange);
    window.removeEventListener("storage", onChange);
  };
}

/**
 * La campana de menciones. Vive en el layout, así que no se vuelve a montar al navegar: por eso
 * sigue al token y no la ruta. Cambiar de cuenta cambia el token, y con él se descarta la lista de
 * la cuenta anterior antes de pedir la nueva. Sin sesión no muestra nada.
 */
export function NotificationBell() {
  const token = useSyncExternalStore(subscribeToSession, getToken, () => null);
  const [list, setList] = useState<NotificationList | null>(null);
  const [open, setOpen] = useState(false);

  const load = useCallback(() => {
    const requestedWith = getToken();
    api
      .listNotifications()
      .then((fresh) => {
        // Si mientras tanto se cambió de cuenta, esta respuesta es de la anterior.
        if (getToken() === requestedWith) setList(fresh);
      })
      // Si no se pudo (servidor caído, sesión vencida), se deja lo último que se vio: una campana
      // que parpadea a cero por un error de red sería peor que una desactualizada.
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (token === null) return;
    load();
    const timer = setInterval(load, NOTIFICATIONS_POLL_MS);
    // Al volver a la pestaña el dato puede tener minutos: se pide en el acto.
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      // Sesión cerrada o cambiada: nada de la cuenta anterior se queda en pantalla.
      setList(null);
      setOpen(false);
    };
  }, [token, load]);

  if (token === null) return null;

  const unread = list?.unreadCount ?? 0;

  async function markRead(id: number) {
    await api.markNotificationRead(id).catch(() => {});
    load();
  }

  async function markAllRead() {
    await api.markAllNotificationsRead().catch(() => {});
    load();
  }

  return (
    <nav
      aria-label="Menciones"
      style={{ position: "fixed", top: "0.75rem", right: "0.75rem", zIndex: 10 }}
    >
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        data-testid="notification-bell"
        data-unread={unread}
      >
        Menciones{unread > 0 && ` (${unread > 99 ? "99+" : unread})`}
      </button>
      {open && (
        <div
          role="region"
          aria-label="Lista de menciones"
          style={{
            marginTop: "0.25rem",
            width: "min(22rem, 90vw)",
            maxHeight: "60vh",
            overflowY: "auto",
            padding: "0.5rem",
            border: "1px solid #8884",
            borderRadius: 6,
            background: "Canvas",
            color: "CanvasText",
          }}
        >
          {list === null || list.items.length === 0 ? (
            <p style={{ margin: 0 }}>No tienes menciones.</p>
          ) : (
            <>
              {unread > 0 && (
                <button type="button" onClick={markAllRead}>
                  Marcar todas como leídas
                </button>
              )}
              <ul style={{ listStyle: "none", padding: 0, margin: "0.5rem 0 0" }}>
                {list.items.map((n) => (
                  <li
                    key={n.id}
                    data-testid={`notification-${n.id}`}
                    data-read={n.read}
                    style={{ padding: "0.25rem 0", fontWeight: n.read ? "normal" : "bold" }}
                  >
                    {n.serverId === null ? (
                      <span>{n.authorName} te mencionó</span>
                    ) : (
                      <Link
                        href={`/servers/${n.serverId}/channels/${n.channelId}`}
                        onClick={() => {
                          setOpen(false);
                          if (!n.read) void markRead(n.id);
                        }}
                      >
                        {n.authorName} te mencionó en # {n.channelName}
                      </Link>
                    )}
                    <br />
                    <small style={{ opacity: 0.7 }}>{n.snippet}</small>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </nav>
  );
}
