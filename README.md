# realtime-chat

[Español](README.es.md) · **English**

A Discord-style real-time chat (servers, public and private channels, invite links, and
later presence, mentions, attachments and search) built around **two guarantees that are
tested on purpose**, not just claimed:

1. **Delivery:** every confirmed message reaches every channel member **exactly once and in
   the same order for everyone**, even across reconnects, client retries and several backend
   instances.
2. **Authorization:** nobody reads or writes a channel they don't belong to, over HTTP or
   WebSocket, **not even one second after being kicked**.

![Java 21](https://img.shields.io/badge/java-21-blue) ![Spring Boot 4.1](https://img.shields.io/badge/spring%20boot-4.1-green) ![License: MIT](https://img.shields.io/badge/license-MIT-green)

> **Status: milestone 4 of 9.** Authorization over HTTP and WebSocket, message ordering, and
> real-time delivery across two backend instances are built and verified, including a delivery
> harness with fixed seeds. Presence, mentions, attachments and search are not built yet.
> Nothing below claims a result that does not exist.

## Status

| Milestone | What | State |
|---|---|---|
| 1 | Auth (bcrypt + JWT), users, servers, Flyway | done |
| 2 | Channels, invite links, roles, `AccessPolicy`, HTTP authorization matrix | done |
| 3 | Messages: per-channel `seq`, idempotent send, cursor pagination | done |
| 4 | Real time: STOMP relay to RabbitMQ, two instances, resync by `seq`, delivery harness | **done** |
| 5 | Presence and rate limiting | planned |
| 6 | Mentions: outbox, worker, retries, dead-letter queue | planned |
| 7 | Attachments (presigned S3 URLs) | planned |
| 8 | Search and unread counts | planned |
| 9 | Deployment, end-to-end tests, measurements | planned |

## What is already proven (milestones 2 to 4)

- **A single `AccessPolicy` decides every access.** Controllers never check permissions on
  their own. It reads roles from the database on every request, never from the JWT, so a
  kicked user is out on the very next request even though their token is still valid.
- **404 when you can't see the resource, 403 when you can see it but lack the role.** A
  non-member gets 404 on a private channel, so the response doesn't confirm it exists.
- **An authorization matrix as tests:** every endpoint × 6 kinds of user (owner, admin,
  member, member of another server, unauthenticated, kicked a second ago), one test per
  cell, 144 in total, against a real PostgreSQL. A test fails if an endpoint exists that is
  not in the matrix, and another fails if [`docs/AUTHZ.md`](docs/AUTHZ.md) drifts from it.
  I broke a permission on purpose and added an unlisted endpoint to check the matrix
  notices; it did.
- **Invite usage limits survive concurrency.** Accepting an invite spends a use with one
  atomic `UPDATE … WHERE uses < max_uses`. With `max_uses = 1`, ten simultaneous accepts let
  exactly one in (also tested with a limit of 3).
- **Per-channel `seq` with no repeats and no gaps.** 50 simultaneous sends to one channel get
  `seq` 1 to 50. Swapping the atomic `UPDATE … RETURNING` for a naive `SELECT max(seq) + 1`
  makes the test fail with duplicate `seq` values.
- **Retries never duplicate and never leave a gap.** Ten simultaneous retries of one message
  (same `client_msg_id`) store it once and leave `last_seq = 1`: the retries that lose the
  race roll back the `seq` they reserved ([ADR 0005](docs/adr/0005-envio-idempotente-sin-huecos.md)).
  Without that rollback the test ends at `last_seq = 10`.
- **Cursor pagination by `seq`:** `after_seq` to catch up, `before_seq` for history. The
  channel page uses both, and resyncs on its own when a sent message reveals a gap.

- **Live delivery across two instances.** A message saved on instance A reaches a client
  connected to instance B, in the same order, through a RabbitMQ STOMP relay. Notifications
  go out only after the commit: a rolled-back send publishes nothing, a replayed send
  publishes nothing, and ten simultaneous retries publish exactly one frame.
- **Nobody can write through the WebSocket.** Messages are only written over HTTP; every
  STOMP `SEND` is rejected, because with the relay a `SEND` to `/topic/channel.N` would reach
  subscribers without going through the `seq` or `AccessPolicy`
  ([ADR 0006](docs/adr/0006-envio-solo-por-http.md)). `SUBSCRIBE` goes through `AccessPolicy`
  and reads the database on every subscription, and a second matrix covers it
  (8 rows × 6 user types, 48 cells, in [`docs/AUTHZ.md`](docs/AUTHZ.md)).
- **Kicking someone closes their sockets, on every instance.** The instance that handles the
  kick closes its own sockets and announces it on a RabbitMQ fanout exchange so the other
  instance does the same ([ADR 0007](docs/adr/0007-cerrar-sockets-al-expulsar.md)). With a
  still-valid token the kicked user can reconnect but gets 404 on every subscription.
- **A delivery harness ([`harness/`](harness)).** 8 clients send 640 messages at once to
  one channel through a load balancer in front of two instances, disconnect and reconnect to
  either instance, resend confirmed messages, and one instance is restarted mid-run. Six
  seeded runs ended with zero violations of the four invariants (complete, no duplicates,
  same order, faithful); see [`docs/DELIVERY.md`](docs/DELIVERY.md) for the numbers and what
  they do not prove. Its first run on seed 2 **failed** and found a real client bug (the
  resync cursor must be the end of the gap-free run, not the highest `seq`); that is fixed
  and written up there.

363 backend tests (unit, `@WebMvcTest`, Testcontainers integration with real PostgreSQL
and RabbitMQ) and 40 harness tests, Spotless-formatted.

## The design in 30 seconds

The chat is the easy part. The two hard problems, and the planned answer to each
(details in [`DESIGN.md`](DESIGN.md), decisions in [`docs/adr/`](docs/adr)):

- **Ordering and loss:** each message gets a `seq` that grows by one inside its channel,
  assigned by the database in the same transaction that stores the message. Clients
  remember the last `seq` they saw, so reconnecting is `GET …/messages?after_seq=N`, a gap
  (N+2 without N+1) is detected and fetched, and duplicates are dropped. A client-generated
  `client_msg_id` with `UNIQUE (author_id, client_msg_id)` makes retries idempotent.
- **Several instances:** STOMP over a RabbitMQ broker relay instead of Spring's in-memory
  broker, so a subscriber on instance B receives what was sent through A. Notifications are
  published only after the transaction commits, and mention events go through an outbox
  table so they can't be lost.
- **Authorization over WebSocket:** the same `AccessPolicy` runs on every `SUBSCRIBE`, every
  `SEND` is rejected (messages are written over HTTP only), and kicking someone closes their
  open sessions on every instance.

```mermaid
flowchart LR
  FE[Next.js] -- HTTP --> BE[Spring Boot<br/>instances A and B]
  FE -- WebSocket STOMP --> BE
  BE --> PG[(PostgreSQL<br/>source of truth)]
  BE --> RD[(Redis<br/>presence, rate limit)]
  BE --> MQ[[RabbitMQ<br/>STOMP relay + queues]]
  PG -. outbox .-> MQ --> W[Notification worker]
  FE -. presigned URLs .-> S3[(S3)]
```

PostgreSQL, the REST API, the frontend, the RabbitMQ STOMP relay and the two-instance cluster
exist today; Redis (presence, rate limiting) and the notification worker arrive in
milestones 5 and 6.

## Documents

| File | What it is |
|---|---|
| [`DESIGN.md`](DESIGN.md) | The design, written before the code: problems, decisions, verification plan, milestones |
| [`docs/AUTHZ.md`](docs/AUTHZ.md) | The authorization rules and the full HTTP and STOMP matrices (checked by tests) |
| [`docs/adr/`](docs/adr) | Seven decision records: per-channel `seq`, external broker, publish after commit, outbox, idempotent send without gaps, HTTP-only writes, closing sockets on kick |
| [`docs/DELIVERY.md`](docs/DELIVERY.md) | The delivery guarantee, the harness, its results and its limits |
| [`docs/v2.md`](docs/v2.md) | Ideas left out on purpose (bans, invite revocation…) |

> UI text, API error messages and the design documents are in Spanish (the project started
> as a Spanish-language course project); code identifiers and this README are in English.

## How to run it

Requirements: Docker, Java 21 and Node 20+.

```bash
cp .env.example .env
docker compose up -d                     # PostgreSQL 17, Redis 7, RabbitMQ 4 (STOMP enabled)

cd backend
./mvnw spotless:check verify             # all tests; needs Docker running (Testcontainers)
set -a && source ../.env && set +a       # the app reads these as environment variables
./mvnw spring-boot:run                   # API on :8080

cd ../frontend
npm install && npm run dev               # app on :3000, /api is proxied to the backend
```

To run two instances behind a load balancer (needed by the harness) and run the harness:

```bash
docker compose --profile cluster up -d --build   # backend-a :8081, backend-b :8082, balancer :8088
backend/mvnw -f harness/pom.xml compile exec:java -Dexec.args="--seed 42"
```

To use the cluster from the browser, start the frontend with
`API_URL=http://localhost:8088 NEXT_PUBLIC_WS_URL=ws://localhost:8088/ws npm run dev`.

To regenerate the matrices in `docs/AUTHZ.md` after changing them:
`./mvnw test -Dtest='AuthorizationMatrixTest,StompAuthorizationMatrixTest' -Dauthz.update=true`.

The defaults in `application.properties` (`dev-password`, a development JWT secret) are for
local use only; override them with environment variables (`JWT_SECRET` must be at least 32
characters) in any real deployment.

## Layout

```
backend/    Spring Boot 4.1 (Java 21): auth, server, channel, invite, message, realtime, access
frontend/   Next.js 16: login, register, servers, channels (live), invite page
harness/    delivery harness: N STOMP clients against two instances, four invariants
nginx/      load balancer config for the two-instance cluster
docs/       authorization matrices, delivery results, ADRs, ideas for v2
```

## Out of scope

Voice and video, custom roles, bots and integrations, end-to-end encryption, federation, a
native mobile app, and competing with Discord. See [`DESIGN.md`](DESIGN.md) §2: nothing
outside that list enters before milestone 9.

## License

[MIT](LICENSE)
