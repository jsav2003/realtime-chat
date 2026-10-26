# Chat en tiempo real estilo Discord

Documento de diseño. Escrito antes del código, a propósito.

---

## 1. Qué es este proyecto

Una aplicación de chat en tiempo real con servidores, canales públicos y privados,
mensajes directos, presencia, menciones, adjuntos y búsqueda. Backend en Java con
Spring Boot, frontend en Next.js.

Lo que hace que valga la pena no son las pantallas. Son dos garantías que el sistema
tiene que cumplir y demostrar:

1. **Entrega:** todo mensaje confirmado llega a cada miembro del canal **exactamente una
   vez y en el mismo orden para todos**, aunque el cliente se desconecte, reintente o el
   servidor tenga varias instancias.
2. **Autorización:** nadie lee ni escribe en un canal al que no pertenece, ni por HTTP ni
   por WebSocket, ni siquiera un segundo después de que lo expulsen.

Las dos se verifican con pruebas automatizadas que intentan romperlas a propósito. El
resto del alcance es la aplicación que las rodea.

## 2. Qué NO es

- No hay voz ni video.
- No hay roles personalizados: solo owner, admin y member.
- No hay bots, integraciones ni emojis personalizados.
- No hay cifrado de extremo a extremo ni federación entre servidores.
- No hay app móvil nativa.
- No compite con Discord ni busca usuarios reales.

**Regla de alcance:** nada fuera de esta lista entra hasta terminar el hito 9. Las ideas
nuevas van a `docs/v2.md`.

## 3. El problema central: la garantía de entrega

### 3.1 Lo ingenuo y dónde se rompe

El chat más simple: el cliente manda un mensaje, el servidor lo guarda y lo reenvía por
WebSocket a todos los conectados. Funciona en una demo. Se rompe en cinco casos
concretos:

| Caso | Qué pasa con lo ingenuo |
|---|---|
| El cliente pierde señal 20 segundos | Los mensajes de esos 20 segundos no le llegan nunca. Al volver ve un hueco y no sabe que existe |
| El cliente reenvía porque no recibió confirmación | El mensaje aparece dos veces |
| Dos personas escriben en el mismo instante | Cada una puede ver los mensajes en un orden distinto |
| El backend corre en dos instancias | Quien está conectado a la instancia B no recibe lo que se envió por la A |
| El servidor se cae entre guardar y reenviar | El mensaje queda guardado pero nadie lo recibió en vivo |

El cuarto caso es el más traicionero, porque en desarrollo siempre hay una sola instancia
y nunca aparece. La documentación de Spring lo dice directamente: el broker simple que
trae por defecto "no es adecuado para clustering".

### 3.2 El diseño

**Un número de secuencia por canal.** Cada mensaje recibe un `seq` que crece de uno en
uno dentro de su canal, asignado por la base de datos en la misma transacción que guarda
el mensaje:

```sql
UPDATE channels SET last_seq = last_seq + 1 WHERE id = $1 RETURNING last_seq;
INSERT INTO messages (channel_id, seq, author_id, client_msg_id, body) VALUES (...);
```

El `UPDATE` sobre la fila del canal es atómico en `READ COMMITTED`: dos envíos
simultáneos al mismo canal se ordenan solos, porque el segundo espera al primero. Es la
estrategia E2 del sistema de reservas, aplicada a otro problema. El costo es que los
envíos a un mismo canal se serializan sobre esa fila; con el volumen de un chat es
aceptable, y el informe lo mide.

El orden lo define `seq`, nunca la hora del cliente. Los relojes de dos computadores no
coinciden, y ordenar por ellos es la causa del tercer caso de la tabla.

**Cada cliente recuerda el último `seq` que vio en cada canal.** Con eso:

- **Al reconectar,** pide `GET /channels/{id}/messages?after_seq=N` y recibe exactamente
  lo que se perdió. Resuelve el primer caso.
- **Si recibe el `seq` N+2 esperando el N+1,** detecta el hueco y lo pide por HTTP.
  Resuelve el quinto caso: el mensaje que no llegó en vivo se recupera igual.
- **Si recibe un `seq` que ya tenía,** lo descarta.

**Idempotencia del envío.** El cliente genera un `client_msg_id` único por mensaje y lo
manda en cada reintento. Con `UNIQUE (author_id, client_msg_id)`, un reintento devuelve el
mensaje ya guardado en vez de crear otro. Resuelve el segundo caso.
Si dos reintentos del mismo mensaje llegan a la vez, el que pierde la carrera ya reservó un
`seq`; su transacción se revierte para devolverlo, y así la secuencia nunca queda con
huecos (ADR 0005). Reusar la clave en otro canal es un error del cliente: 409.

**Broker externo para varias instancias.** El WebSocket usa STOMP con el relay de Spring
hacia RabbitMQ (`enableStompBrokerRelay`), no el broker simple en memoria. Cualquier
instancia publica en RabbitMQ y RabbitMQ entrega a todas. Resuelve el cuarto caso.

**Publicar después del commit, nunca antes.** El aviso por WebSocket se emite cuando la
transacción ya confirmó (`@TransactionalEventListener(phase = AFTER_COMMIT)`). Si se
publicara antes y la transacción fallara, los clientes verían un mensaje que no existe.
Si el servidor se cae después del commit y antes de publicar, la resincronización por
`seq` lo recupera.

### 3.3 Una consecuencia útil: los no leídos

Con `seq`, el contador de no leídos de un canal es una resta:

```
no_leídos = channels.last_seq − read_state.last_read_seq
```

Una operación constante, sin contar mensajes. La alternativa ingenua,
`COUNT(*) WHERE created_at > última_lectura`, recorre mensajes por cada canal de cada
usuario en cada carga de la barra lateral.

## 4. El segundo problema: autorización multi-servidor

Cada servidor es un espacio aislado. Las fallas típicas:

- **Acceso por ID adivinado.** El endpoint revisa que el usuario esté autenticado pero no
  que pertenezca al servidor del canal. Cambiando el número en la URL se lee otro
  servidor.
- **WebSocket sin revisar.** Los endpoints HTTP están protegidos, pero la suscripción
  STOMP a `/topic/channel.123` no lo está. Es el hueco más común, porque la seguridad de
  Spring sobre HTTP no cubre automáticamente los mensajes STOMP.
- **Expulsión que no corta.** Un admin expulsa a alguien, pero su suscripción abierta
  sigue recibiendo mensajes hasta que recarga.
- **Respuesta que filtra existencia.** Devolver 403 en un canal privado confirma que
  existe. Para quien no es miembro, la respuesta correcta es 404.

**El diseño:** toda decisión de acceso pasa por un solo componente (`AccessPolicy`) que
recibe usuario, recurso y acción. Lo usan los controladores HTTP, el interceptor de
mensajes STOMP en `SUBSCRIBE` y en `SEND`, y el servicio de expulsión. Con el relay, las
suscripciones viven en el broker y el backend no puede quitar una sola de forma
sencilla. Por eso, al expulsar, se cierran las sesiones WebSocket abiertas del usuario.
Cuando reconecta, la `AccessPolicy` ya rechaza la suscripción a los canales de ese
servidor.

## 5. Problemas secundarios

**Presencia en varias pestañas e instancias.** Un usuario está en línea si tiene al
menos una conexión viva en cualquier instancia. Cada conexión escribe en Redis una clave
con TTL que se renueva con cada heartbeat. Si la pestaña se cierra sin avisar, la clave
vence sola. "En línea" es "existe al menos una clave".

**Rate limiting.** Un límite de mensajes por minuto por usuario, con token bucket en
Redis. La versión ingenua lee el contador, decide y escribe en dos pasos, y bajo
concurrencia deja pasar más de lo permitido. La versión correcta hace todo en un solo
script Lua, que Redis ejecuta de forma atómica.

**Notificaciones de menciones.** Un `@usuario` genera una notificación que procesa un
worker aparte por RabbitMQ. Si el mensaje se guarda y el evento se pierde antes de llegar
a la cola, la mención nunca se notifica. Se resuelve con una tabla *outbox* escrita en la
misma transacción que el mensaje, que un publicador lee y envía a la cola. El worker
recibe al menos una vez, así que deduplica por `(message_id, user_id)`. Los fallos se
reintentan con espera creciente y terminan en una cola de mensajes muertos.

**Adjuntos.** El archivo nunca pasa por el backend: el cliente pide una URL prefirmada de
S3, sube directo, y el backend solo registra el adjunto tras verificar que el objeto
existe y respeta el tamaño máximo.

## 6. Arquitectura

```
Next.js ──HTTP (REST)──────▶ ┌──────────────┐
   │                         │ Spring Boot  │──▶ PostgreSQL (fuente de verdad)
   └──WebSocket (STOMP)────▶ │ instancias   │──▶ Redis (presencia, rate limit)
                             │ A y B        │──▶ RabbitMQ (relay STOMP + colas)
                             └──────────────┘
                                    │
                             outbox ──▶ RabbitMQ ──▶ worker de notificaciones
                                                  S3 (adjuntos, URLs prefirmadas)
```

- **Dos instancias del backend desde el hito 4**, detrás de un balanceador en Docker
  Compose. Si el sistema solo se prueba con una, la mitad de la §3 nunca se ejercita.
- **PostgreSQL es la única fuente de verdad.** Redis y RabbitMQ pueden perderse y
  reiniciarse sin perder mensajes; en el peor caso, los clientes se resincronizan por
  `seq`.

## 7. Modelo de datos

```
users            (id, email, password_hash, display_name)
servers          (id, name, owner_id)
server_members   (server_id, user_id, role)            -- owner | admin | member
invites          (code, server_id, expires_at, max_uses, uses)
channels         (id, server_id, name, kind, last_seq) -- text | private | dm
channel_members  (channel_id, user_id)                  -- solo canales privados y DMs
messages         (id, channel_id, seq, author_id, client_msg_id, body, created_at)
                 UNIQUE (channel_id, seq)
                 UNIQUE (author_id, client_msg_id)
read_state       (user_id, channel_id, last_read_seq)
attachments      (id, message_id, s3_key, size, content_type)
outbox           (id, event_type, payload, created_at, published_at)
```

Un mensaje directo es un canal de tipo `dm` con exactamente dos miembros y sin servidor.
Así reutiliza toda la lógica de entrega, secuencia y no leídos.

## 8. Verificación

Esta sección es el proyecto. Sin ella queda un clon de Discord más.

### 8.1 Arnés de entrega

Un programa que simula N clientes STOMP contra las dos instancias del backend:

- Todos envían mensajes al mismo canal a la vez, cada uno con su `client_msg_id`.
- Se desconectan al azar y reconectan, a veces a la otra instancia.
- Algunos reenvían mensajes ya confirmados, con el mismo `client_msg_id`.
- En medio de la corrida se reinicia una instancia del backend.

Al terminar, cuatro invariantes:

1. **Completo:** cada cliente tiene todos los `seq` desde 1 hasta `last_seq`, sin huecos.
2. **Sin duplicados:** ningún `seq` aparece dos veces en ningún cliente.
3. **Mismo orden:** todos los clientes tienen exactamente la misma secuencia.
4. **Fiel:** todo envío que recibió confirmación aparece exactamente una vez, y ninguno
   sin confirmación aparece duplicado.

Semilla fija, para que cualquier falla se pueda reproducir. Resultado en
`docs/DELIVERY.md`: corridas, desconexiones inyectadas, reinicios, y violaciones de cada
invariante, que deben ser cero.

### 8.2 Matriz de autorización

Una tabla de verdad generada como tests: cada endpoint HTTP y cada destino STOMP, cruzado
con cada tipo de usuario (owner, admin, member, miembro de otro servidor, no autenticado,
expulsado hace un segundo), con la respuesta esperada (permitido, 401, 403 o 404). Es 404
si el usuario no puede ver el recurso (no es miembro, o el canal es privado y no está en
él) y 403 si lo ve pero su rol no alcanza. Un test por celda. Si alguien agrega un endpoint y no lo pone en la matriz, un test falla.

### 8.3 Concurrencia de componentes

- **Rate limiter:** 100 solicitudes simultáneas contra un bucket de capacidad 20, sin
  recarga durante la prueba. Deben pasar exactamente 20.
- **Secuencia:** 50 envíos simultáneos al mismo canal producen los `seq` 1 a 50, sin
  repetidos ni saltos.
- **Outbox:** se mata el worker en momentos aleatorios. Toda mención termina notificada
  exactamente una vez.

### 8.4 Pruebas por capa y de extremo a extremo

- Unitarias con JUnit 5 y Mockito para la lógica de servicios.
- `@WebMvcTest` y `@DataJpaTest` por capa.
- Integración con Testcontainers: Postgres, Redis y RabbitMQ reales, no simulados.
- Playwright con dos navegadores: uno envía, el otro recibe, y uno se desconecta y vuelve.

### 8.5 Medición

Latencia de entrega (desde que se envía hasta que cada cliente lo recibe) en p50 y p99, y
mensajes por segundo en un canal, con una y con dos instancias. Se reporta el hardware.

## 9. Plan por hitos

| Hito | Horas | Terminado cuando |
|---|---|---|
| 1 · Auth, usuarios y servidores | 14 | Registro y login con bcrypt y JWT; crear servidor y listar los míos; Flyway; `@ControllerAdvice` |
| 2 · Canales, invitaciones y permisos | 14 | `AccessPolicy` en cada endpoint; invitaciones por link; matriz de autorización HTTP en verde |
| 3 · Mensajes | 14 | `seq` por canal, `client_msg_id`, paginación por cursor; test de 50 envíos simultáneos en verde |
| 4 · Tiempo real | 20 | STOMP con relay a RabbitMQ, dos instancias, resincronización por `seq`, autorización en `SUBSCRIBE`; **arnés de entrega en verde** |
| 5 · Presencia y rate limiting | 12 | Presencia con TTL en varias pestañas; "escribiendo…"; token bucket atómico |
| 6 · Menciones y notificaciones | 14 | Outbox, worker, reintentos y cola de mensajes muertos; test de worker interrumpido en verde |
| 7 · Adjuntos | 10 | URLs prefirmadas de S3 y validación de tamaño |
| 8 · Búsqueda y no leídos | 10 | Full-text de Postgres; no leídos por resta de `seq` |
| 9 · Despliegue y cierre | 16 | CI/CD a AWS, Playwright, medición de la §8.5, documentación |

Total estimado: unas 124 horas.

### Reglas de rescate

- Si hay retraso, se recortan la búsqueda (hito 8) y los adjuntos (hito 7).
- **El arnés de entrega y la matriz de autorización no se recortan.** Son el proyecto.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| GitHub Actions bloqueado por facturación en la cuenta | Resolverlo antes del hito 1; mientras tanto, la suite completa corre en local con un comando |
| Costos de AWS con servicios administrados | Redis y RabbitMQ en contenedores en la misma instancia para la demo; alerta de presupuesto desde el primer día, no en el hito 9 |
| Todo funciona con una instancia y falla con dos | Dos instancias obligatorias desde el hito 4 |
| La curva de Spring Boot alarga los primeros hitos | Los hitos 1 y 2 tienen holgura; ninguno depende de tiempo real |
| Crecer el alcance con funciones de Discord | La lista de la §2 y `docs/v2.md` |

## 11. Forma de trabajo

- **Monorepo:** `backend/` (Java 21, Spring Boot 4, Maven) y `frontend/` (Next.js).
- **Editores:** IntelliJ IDEA para el backend y VS Code para el frontend.
- **Entorno:** `docker compose up` levanta Postgres, Redis, RabbitMQ y las dos instancias.
  `.env.example` en el repo, `.env` nunca.
- **Calidad automática:** Spotless en el backend, ESLint y Prettier en el frontend, hooks
  de pre-commit y CI en cada PR.
- **Flujo:** un milestone por hito, un issue por tarea, una rama y un PR por issue,
  Conventional Commits, `main` protegida.
- **Decisiones:** un ADR corto por cada decisión importante en `docs/adr/`. Las primeras
  cuatro ya están en este documento: secuencia por canal, broker externo, publicar
  después del commit y outbox.
- **Regla con la IA (cambiada el 2026-10-23):** Claude escribe el código, y Sebastián lo
  revisa y tiene que poder explicarlo línea por línea, sobre todo la secuencia, la
  resincronización y la autorización. Esas tres partes no se aceptan sin entenderlas;
  si algo no se puede explicar, se pregunta antes de seguir. *(La versión original decía
  que el backend lo escribía Sebastián y la IA solo revisaba.)*

## 12. Qué queda en el repositorio

- `DESIGN.md` — este documento.
- `docs/DELIVERY.md` — la garantía de entrega, el arnés y sus resultados.
- `docs/AUTHZ.md` — la matriz de autorización completa.
- `docs/adr/` — las decisiones de diseño.
- `docs/v2.md` — lo que quedó fuera, a propósito.
- Un GIF en el README: dos navegadores, uno pierde la conexión, se escriben mensajes,
  vuelve, y recupera exactamente lo que se perdió.

## 13. Glosario

- **STOMP:** protocolo de mensajería sobre WebSocket; define suscribirse a un destino y
  enviar a uno.
- **Broker relay:** configuración de Spring que reenvía los mensajes STOMP a un broker
  externo, como RabbitMQ, para que varias instancias compartan suscriptores.
- **`seq`:** número de secuencia de un mensaje dentro de su canal; define el orden y
  permite detectar huecos.
- **Resincronización:** pedir por HTTP los mensajes posteriores al último `seq` visto,
  al reconectar o al detectar un hueco.
- **Idempotencia:** repetir una operación produce el mismo resultado que hacerla una vez.
- **Outbox:** tabla donde se escribe un evento en la misma transacción que el dato, para
  publicarlo después sin riesgo de perderlo.
- **Al menos una vez:** garantía de entrega en la que un mensaje puede llegar repetido,
  pero nunca se pierde. Obliga a que quien lo recibe deduplique.
- **TTL:** tiempo de vida de una clave en Redis; al vencer, la clave desaparece sola.
- **Token bucket:** algoritmo de rate limiting que acumula permisos a ritmo fijo y gasta
  uno por solicitud.
- **URL prefirmada:** enlace temporal de S3 que permite subir o bajar un archivo sin
  credenciales propias.
