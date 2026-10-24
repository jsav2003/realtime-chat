const TOKEN_KEY = "chat.token";
const NEXT_KEY = "chat.next";

export type ApiErrorBody = {
  status: number;
  error: string;
  message: string;
  fields: Record<string, string>;
};

export class ApiError extends Error {
  constructor(public body: ApiErrorBody) {
    super(body.message);
  }
}

export type AuthResponse = {
  token: string;
  user: { id: number; email: string; displayName: string };
};

export type Server = { id: number; name: string; ownerId: number };

export type Role = "owner" | "admin" | "member";

export type ServerDetail = Server & { myRole: Role };

export type Channel = {
  id: number;
  serverId: number;
  name: string;
  kind: "text" | "private";
};

export type Invite = {
  code: string;
  path: string;
  expiresAt: string | null;
  maxUses: number | null;
  uses: number;
};

export type InvitePreview = { code: string; serverName: string };

export const getToken = () =>
  typeof window === "undefined" ? null : localStorage.getItem(TOKEN_KEY);

export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);

export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

/** Adónde volver después de iniciar sesión (por ejemplo, al abrir un link de invitación). */
export const setNext = (path: string) => sessionStorage.setItem(NEXT_KEY, path);

export function takeNext(): string | null {
  const path = sessionStorage.getItem(NEXT_KEY);
  sessionStorage.removeItem(NEXT_KEY);
  // Solo rutas internas: nada de redirigir a otro sitio.
  return path && path.startsWith("/") && !path.startsWith("//") ? path : null;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiErrorBody | null;
    throw new ApiError(
      body ?? {
        status: res.status,
        error: "unknown",
        message: "Algo salió mal",
        fields: {},
      },
    );
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  register: (email: string, password: string, displayName: string) =>
    request<AuthResponse>("/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, password, displayName }),
    }),
  login: (email: string, password: string) =>
    request<AuthResponse>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    }),
  listServers: () => request<Server[]>("/servers"),
  createServer: (name: string) =>
    request<Server>("/servers", { method: "POST", body: JSON.stringify({ name }) }),
  getServer: (id: number) => request<ServerDetail>(`/servers/${id}`),
  listChannels: (serverId: number) => request<Channel[]>(`/servers/${serverId}/channels`),
  createChannel: (serverId: number, name: string, kind: Channel["kind"]) =>
    request<Channel>(`/servers/${serverId}/channels`, {
      method: "POST",
      body: JSON.stringify({ name, kind }),
    }),
  createInvite: (serverId: number) =>
    request<Invite>(`/servers/${serverId}/invites`, { method: "POST", body: "{}" }),
  previewInvite: (code: string) =>
    request<InvitePreview>(`/invites/${encodeURIComponent(code)}`),
  acceptInvite: (code: string) =>
    request<Server>(`/invites/${encodeURIComponent(code)}/accept`, { method: "POST" }),
};
