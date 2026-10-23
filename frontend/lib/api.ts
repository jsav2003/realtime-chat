const TOKEN_KEY = "chat.token";

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

export const getToken = () =>
  typeof window === "undefined" ? null : localStorage.getItem(TOKEN_KEY);

export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);

export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

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
};
