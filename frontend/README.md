# frontend

Next.js 16 (App Router) client for [realtime-chat](../README.md): login, registration, your
servers, a server's channels and the invite page.

```bash
npm install
npm run dev      # http://localhost:3000
npm run lint
npm run build
```

The browser only talks to `/api`; `next.config.ts` rewrites it to the backend
(`API_URL`, default `http://localhost:8080`), so no CORS setup is needed. The JWT is kept in
`localStorage` for now.
