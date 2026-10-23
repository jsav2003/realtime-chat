# realtime-chat

Discord-style real-time chat (servers, channels, DMs, presence, mentions, attachments,
search) built around two guarantees that are tested on purpose:

1. **Delivery:** every confirmed message reaches every channel member exactly once and in
   the same order for everyone, even across reconnects, retries and multiple instances.
2. **Authorization:** nobody reads or writes a channel they don't belong to, over HTTP or
   WebSocket, not even one second after being kicked.

Java 21 + Spring Boot 4 (`backend/`), Next.js (`frontend/`), PostgreSQL, Redis, RabbitMQ.

The design document is [`DESIGN.md`](DESIGN.md) (in Spanish), with decisions in
[`docs/adr/`](docs/adr). Status: milestone 1 of 9.

## Run

```
cp .env.example .env
docker compose up -d
cd backend && ./mvnw verify
```
