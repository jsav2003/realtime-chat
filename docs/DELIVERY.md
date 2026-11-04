# Garantía de entrega

**La garantía:** todo mensaje que el servidor confirmó llega a cada miembro del canal
**exactamente una vez y en el mismo orden**, aunque haya desconexiones, reintentos o dos
instancias del backend. Se sostiene con tres piezas (DESIGN.md §3.2):

- un `seq` por canal, asignado por Postgres en la misma transacción que guarda el mensaje;
- `client_msg_id`, para que un reintento devuelva el mensaje ya guardado en vez de crear otro
  (ADR 0005);
- entrega en vivo por STOMP con relay a RabbitMQ y, **siempre**, resincronización por `seq`
  (`GET /channels/{id}/messages?after_seq=N`) para lo que no llegó en vivo.

La entrega en vivo es una optimización; la que garantiza es la resincronización. Por eso el
arnés mide las dos por separado.

## El arnés

[`harness/`](../harness) simula N clientes STOMP contra las **dos instancias** del backend,
detrás del balanceador de `docker compose --profile cluster`:

- todos envían al mismo canal a la vez, cada mensaje con su `client_msg_id`;
- se desconectan al azar y reconectan, a veces a la otra instancia;
- algunos reenvían mensajes ya confirmados con el mismo `client_msg_id`;
- a mitad de la corrida se reinicia una instancia (`docker compose restart`).

Cada cliente hace lo mismo que el frontend: se conecta con su JWT, se suscribe con `receipt`
y, cuando el servidor confirma la suscripción, pide por HTTP lo posterior a lo que tiene.
Al terminar, cada cliente hace una resincronización final y se comprueban cuatro invariantes
contra el historial completo que lee el servidor:

1. **Completo:** cada cliente tiene todos los `seq` de 1 a `last_seq`, sin huecos.
2. **Sin duplicados:** ningún `seq` corresponde a dos mensajes distintos ni ningún mensaje a
   dos `seq`, ni en el servidor ni en un cliente.
3. **Mismo orden:** todos los clientes tienen exactamente la misma secuencia que el servidor.
4. **Fiel:** todo envío confirmado aparece exactamente una vez y con el `seq` que se
   confirmó; ningún envío aparece duplicado; no aparece nada que nadie envió; y reenviar un
   mensaje ya confirmado devuelve 200 con el mismo `seq`.

La **semilla** fija el plan completo: qué manda cada cliente, qué reenvía, a quién se
desconecta, a qué instancia vuelve y cuál instancia se reinicia. Las fallas se disparan por
progreso (cuántos envíos ya se confirmaron) y no por reloj. Lo que la semilla no fija es
cómo el sistema operativo intercala los hilos, por eso las invariantes se comprueban sobre
el resultado y no sobre la traza (y por eso la misma semilla puede dar resultados distintos,
como se ve más abajo).

### Cómo correrlo

```bash
docker compose --profile cluster up -d --build
backend/mvnw -f harness/pom.xml compile exec:java -Dexec.args="--seed 42"
```

Sale con código 0 si las cuatro invariantes dan cero violaciones y con 1 si no. Las pruebas
del propio arnés (que cada invariante detecta lo que debe) corren sin Docker:
`backend/mvnw -f harness/pom.xml test`.

## Resultados

Escenario de todas las corridas: 8 clientes × 80 mensajes (640 envíos), hasta 700 ms entre
envíos de un mismo cliente, 15 % de reenvíos deliberados, 12 desconexiones y el reinicio de
una instancia al 20 % de la corrida. Equipo: Windows 11, 8 hilos, Java 23.0.1, todo en una
sola máquina (Docker Desktop).

| Semilla | Confirmados | Reenvíos | Desconexiones | Reinicio (tardó en volver) | Vistos en vivo | Vistos por resync | Repetidos descartados | Faltaban antes del resync final | Violaciones (1/2/3/4) | Duración |
|---|---|---|---|---|---|---|---|---|---|---|
| 42 | 640 | 85 | 12 | backend-b (48.1 s) | 4898 | 222 | 113 | 0 | 0/0/0/0 | 65.3 s |
| 1 | 640 | 112 | 12 | backend-a (45.7 s) | 4876 | 244 | 137 | 0 | 0/0/0/0 | 62.6 s |
| 2 | 640 | 96 | 12 | backend-a (49.9 s) | 4901 | 219 | 71 | 0 | 0/0/0/0 | 66.0 s |
| 3 | 640 | 94 | 12 | backend-a (57.1 s) | 4850 | 270 | 50 | 0 | 0/0/0/0 | 73.6 s |
| 4 | 640 | 97 | 12 | backend-b (39.3 s) | 4857 | 263 | 119 | 0 | 0/0/0/0 | 55.1 s |
| 5 | 640 | 101 | 12 | backend-a (45.8 s) | 4915 | 205 | 84 | 0 | 0/0/0/0 | 61.2 s |

**Cero violaciones en las seis corridas** (3 840 envíos confirmados, 72 desconexiones, seis
reinicios de instancia).

Cómo leer las columnas:

- *Vistos en vivo / por resync:* cada uno de los 8 clientes ve cada uno de los 640 mensajes
  por primera vez por un solo camino, así que las dos columnas suman siempre 5 120. Entre el
  4,0 % y el 5,3 % de las entregas las tuvo que traer la resincronización: sin ella, el
  resultado sería otro.
- *Repetidos descartados:* entregas de un `seq` que el cliente ya tenía (por ejemplo, un
  mensaje que llega en vivo y también en un resync). Se descartan por `seq` y no cuentan como
  duplicado.
- *Faltaban antes del resync final:* mensajes que a los clientes les faltaban justo antes de
  la resincronización final del arnés, después de dar 3,5 s para que lo que estaba en vuelo
  terminara de llegar. Fue cero en todas: durante la corrida, las resincronizaciones por
  reconexión y por hueco ya habían cerrado todo, no solo la última.
- *Envíos reintentados por falla (no aparece en la tabla):* cero en cinco de las seis corridas y
  uno en la semilla 1 (un envío falló una vez y se confirmó al reintentarlo con el mismo
  `client_msg_id`). Ninguno se rindió sin confirmación. En los reinicios el balanceador
  (`proxy_next_upstream`) usó la otra instancia mientras una estaba caída, así que los envíos
  casi no vieron errores. Los clientes conectados por WebSocket a la
  instancia reiniciada sí perdieron la conexión, reconectaron y se resincronizaron.
- *Tardó en volver:* desde `docker compose restart` hasta que `/actuator/health` responde
  200. La mayor parte es el arranque de Spring Boot dentro de un contenedor.

## Con el límite de mensajes (hito 5)

El hito 5 agregó un límite de mensajes por usuario (ADR 0009: capacidad 20, recarga de 30 por
minuto). El arnés manda 80 mensajes por cliente, así que con el límite por defecto **choca a
propósito**: trata el `429` como «espera `Retry-After` y reintenta con el mismo
`client_msg_id`», sin gastar sus 80 intentos de falla real, y lo cuenta aparte. Los reenvíos
deliberados de mensajes ya confirmados siguen dando 200 con el bucket vacío (no gastan cupo).

Mismo escenario de arriba. La primera fila sube el límite a 100 000 (variables
`RATE_LIMIT_CAPACITY` y `RATE_LIMIT_REFILL_PER_MINUTE` del cluster) para comprobar que el
cambio no alteró nada; las otras dos corren con el límite por defecto.

| Semilla | Límite | Confirmados | Reenvíos | Frenados por 429 y reintentados | Reinicio (tardó en volver) | Vistos en vivo | Vistos por resync | Repetidos descartados | Faltaban antes del resync final | Violaciones (1/2/3/4) | Duración |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 42 | 100 000 | 640 | 85 | 0 | backend-b (62.2 s) | 4910 | 210 | 217 | 0 | 0/0/0/0 | 91.8 s |
| 42 | 20 y 30/min | 640 | 85 | 434 | backend-b (49.0 s) | 5057 | 63 | 78 | 0 | 0/0/0/0 | 132.0 s |
| 7 | 20 y 30/min | 640 | 101 | 446 | backend-a (35.5 s) | 5012 | 108 | 50 | 0 | 0/0/0/0 | 129.6 s |

**Cero violaciones**, y con el límite por defecto 434 y 446 envíos fueron frenados, esperaron y
se confirmaron una sola vez: los 640 envíos de cada corrida quedaron confirmados, ninguno se
rindió y `last_seq` fue 640. Con el límite puesto, las corridas duran más (132 s y 130 s frente a
92 s) por la espera de los clientes frenados, y la resincronización trajo menos entregas (63 y
108 frente a 210). No se investigó por qué; una explicación posible es que los envíos llegan
más espaciados, pero es una hipótesis, no una medición.

Cuidado con qué se concluye de esto: las tres corridas son pocas y la semilla fija el plan,
no el intercalado de hilos. Dicen que el límite no rompe la entrega en estos escenarios, no que
no pueda hacerlo en otros.

## Notificaciones de menciones (hito 6)

**La garantía:** toda mención de un mensaje guardado termina notificada **exactamente una vez**, y
solo a quien puede leer el canal, aunque el worker, el publicador o la conexión a RabbitMQ mueran
en cualquier momento (DESIGN.md §5 y §8.3). «Exactamente una vez» es del **efecto**, no de la
entrega: el evento llega *al menos una vez* y la inserción de la notificación es idempotente.
Se sostiene con cuatro piezas:

- el evento se escribe en la `outbox` **en la misma transacción** que el mensaje y su `seq`
  (ADR 0004, 0011): se confirman o se revierten juntos;
- un publicador por instancia lo lleva a RabbitMQ con `FOR UPDATE SKIP LOCKED` y lo marca como
  publicado solo si el broker lo **confirmó y lo ruteó** (ADR 0012);
- el worker deduplica con `UNIQUE (message_id, user_id)` + `INSERT … ON CONFLICT DO NOTHING`, en
  una sola sentencia (ADR 0013);
- un fallo reintenta por colas de espera de 2 s, 10 s y 1 min, y lo que no tiene arreglo, o agota
  sus intentos, queda en `mentions.dlq` con el motivo (ADR 0013).

### El test del §8.3

`WorkerInterruptedIntegrationTest` (Postgres y RabbitMQ reales): 7 usuarios en un canal, 4
emisores con 50 mensajes cada uno que mencionan al azar a 1–3 personas, y mientras entran:

- el **worker** «muere» antes de procesar un evento (20 %) y después de guardar su notificación y
  antes de confirmarlo (20 %);
- su **conexión** a RabbitMQ se corta a la fuerza 6 veces y el worker se para y se arranca 3;
- dos **publicadores** (como dos instancias) mueren justo después de publicar un lote y antes de
  marcarlo (30 % de los lotes): ese lote sale dos veces.

Al terminar, contra lo que se envió, exige: (1) cada (mensaje, persona) mencionada tiene
exactamente una notificación, (2) no hay ninguna de más, (3) la outbox no tiene pendientes y (4)
la cola de mensajes muertos está vacía. Y exige que la corrida **haya tocado** las ventanas
peligrosas (caídas y duplicados descartados mayores que cero): sin eso, un pase no probaría nada.
Semillas fijas; equipo: Windows 11, 8 hilos, Java 23.0.1, todo en una sola máquina.

| Semilla | Menciones | Caídas del worker | De ellas, tras guardar y antes del ack | Caídas del publicador | Duplicados descartados | Violaciones |
|---|---|---|---|---|---|---|
| 42 | 408 | 131 | 56 | 35 | 241 | 0 |
| 7 | 397 | 105 | 49 | 40 | 383 | 0 |
| 2026 | 407 | 107 | 48 | 26 | 194 | 0 |

**Cero violaciones en las tres corridas** (1 212 menciones). Las caídas del test no son un proceso
que desaparece: son excepciones que salen por fuera del listener, y para RabbitMQ equivalen a un
mensaje sin confirmar (vuelve a la cola). Lo que sí mata un proceso de verdad es el arnés de la
sección siguiente.

### El arnés con SIGKILL real

`MentionsHarness` (ver [`harness/README.md`](../harness/README.md)) hace lo mismo con procesos de
verdad, contra el cluster de `docker compose`: 8 clientes envían 40 mensajes cada uno por el
balanceador (70 % con una o dos menciones, 15 % reenviados a propósito), y a ciertos progresos de
la corrida, fijados por la semilla, se le manda **SIGKILL** al contenedor `worker`
(`docker compose kill`), que se deja caído entre 0,3 y 3 s y se levanta de nuevo. Al terminar
compara lo que cada persona recibe por `GET /notifications` con los mensajes que quedaron
guardados, y comprueba tres invariantes: toda mención guardada terminó notificada (*completa*),
nadie tiene dos notificaciones por el mismo mensaje (*sin duplicados*) y nadie recibió una de un
mensaje que no lo mencionaba (*fiel*). Mismo equipo; el límite de mensajes está en su valor por
defecto, así que los emisores esperan sus 429 como en el hito 5.

| Semilla | Mensajes | Menciones esperadas | Notificaciones recibidas | Muertes del worker | Reenvíos | Envíos frenados por 429 | Hasta tener todo, tras el último envío | Violaciones (completa / sin duplicados / fiel) |
|---|---|---|---|---|---|---|---|---|
| 42 | 320 | 340 | 340 | 6 de 6 | 43 | 119 | 30,1 s | 0 / 0 / 0 |
| 7 | 320 | 335 | 335 | 6 de 6 | 41 | 126 | 29,3 s | 0 / 0 / 0 |

**Cero violaciones:** las 675 menciones de las dos corridas terminaron notificadas exactamente una
vez, y los 84 reenvíos de mensajes ya confirmados no notificaron de nuevo. Los ~30 s finales no son
el worker procesando sino su regreso: tras un SIGKILL arranca la aplicación entera de Spring Boot
(54 s al levantar el cluster), y mientras tanto los eventos esperan en la cola, que es justo lo que
se quería ver. Lo que esta corrida **no** distingue es cuántas de las 12 muertes cayeron con un
evento en proceso; esa ventana (guardado pero sin confirmar) la fuerza el test de arriba, no un
SIGKILL al azar.

### Lo que encontró romperlo a propósito

Un test que nunca falla no demuestra nada, así que cada pieza se rompió y se corrió su test
(todo compila; el código se restauró con `git checkout` después de cada rotura, 02/11):

| Rotura | Test que la detecta | Resultado |
|---|---|---|
| Deduplicación ingenua: «¿ya existe?» y luego «créala», sin `UNIQUE` | `NotificationDedupeConcurrencyIntegrationTest` (160 llamadas a la vez sobre 20 pares) | falla: crea **72 en vez de 20** |
| La misma, contra el test grande | `WorkerInterruptedIntegrationTest` | **pasa las tres semillas** (ver abajo) |
| El publicador marca todo el lote sin mirar lo que el broker confirmó | `OutboxPublisherIntegrationTest` | fallan 3 de 8: el broker que no confirma nada, el evento sin cola, el lote confirmado a medias |
| Los eventos se escriben *después* del commit del mensaje | `MentionOutboxIntegrationTest` | falla 1 de 9: queda un mensaje guardado sin sus menciones (1 donde debía haber 0) |

**La segunda fila es el hallazgo.** Mi plan decía que la deduplicación ingenua haría fallar el
test grande, y no es cierto: tras una caída, el evento se reentrega *después* de que el primer
intento terminó, y hasta «¿ya existe?» lo atrapa. Lo que rompe a la versión ingenua es que dos
workers procesen el mismo evento **a la vez**, y el test grande no lo provoca de forma fiable.
Por eso hay una prueba de concurrencia aparte: sin ella, el §8.3 pasaba en verde con una
deduplicación incorrecta.

Dos errores míos que las pruebas mismas delataron, anotados porque se parecen a los que se
cuelan sin que nadie los vea: (1) el primer contador de fallos de la prueba de reintentos vivía
en una tabla *dentro del trigger que lanza la excepción*, y Postgres lo revertía junto con la
sentencia, así que el presupuesto nunca bajaba y «falla siempre» pasaba por la razón equivocada;
ahora cuenta con una secuencia, que no se revierte. (2) El plan sembrado de menciones del arnés
usaba semillas casi consecutivas para `java.util.Random`, cuya primera salida es casi igual, y las
menciones salían en bloques por cliente; lo notó la prueba de la tasa y ahora la semilla se mezcla.

## Adjuntos (hito 7)

**La garantía:** un adjunto es de **un solo mensaje**, tiene el tamaño que se declaró y solo lo baja
quien puede leer el canal *ahora* (DESIGN.md §5, ADR 0014). No toca la garantía de entrega de los
mensajes: el envío no habla con S3, así que un S3 lento o caído no frena ni reordena un canal.

- La subida firma `Content-Length` y `Content-Type`; `POST /attachments/{id}/complete` hace un `HEAD`
  y borra el objeto y la fila si el tamaño no es el declarado.
- Cada adjunto se une a su mensaje con un `UPDATE … WHERE message_id IS NULL` en la transacción del
  envío; el que pierde la carrera se revierte entero, con su `seq`.
- Bajar es una URL de 60 s pedida por HTTP y revisada con `AccessPolicy`; por el topic STOMP solo
  sale la descripción del archivo.

### La prueba de concurrencia

`AttachmentClaimConcurrencyIntegrationTest` (Postgres y S3 reales): 10 mensajes con `clientMsgId`
distintos, a la vez, reclaman el **mismo** adjunto. Resultado: entra exactamente **1**, los otros 9
reciben 409 y `last_seq` queda en **1** (sin huecos).

### Comprobado que los tests muerden (03/11)

Todo compila; el código se restauró después de cada rotura.

| Rotura | Test que la detecta | Resultado |
|---|---|---|
| Quitar `AND message_id IS NULL` del `UPDATE` que une el adjunto | `AttachmentClaimConcurrencyIntegrationTest` | falla: **10 mensajes** se quedan con el adjunto en vez de 1 |
| No firmar `Content-Length` en la URL de subida | `S3ObjectStorageIntegrationTest` | falla 1 de 3 (`uploadUrlSignsContentLengthAndContentType`) |
| Saltarse el `HEAD` en `complete` | `AttachmentCompleteIntegrationTest` | fallan 2 de 6 (tamaño distinto y subida sin hacer) |
| Descargar sin `AccessPolicy` | `AttachmentDownloadIntegrationTest` | fallan 3 de 9 (un extraño, un privado ajeno y un expulsado) |

### Lo que esto no demuestra

- **Adobe S3Mock no valida la firma de las URLs.** La prueba del `HEAD` sube a propósito 26 bytes a
  una URL firmada para 25 y S3Mock responde 200; por eso el tamaño se hace cumplir con el `HEAD` y
  no hay una prueba de que S3 de verdad rechace el cuerpo con 403. Se verifica en el hito 9.
- **Nada en un navegador real todavía.** La subida desde el compositor (XHR con progreso) y las
  imágenes en el chat pasaron lint, tipos y `next build`; el CORS de S3Mock se comprobó con `curl`
  (preflight y `PUT` con `Origin`), pero no se probó a mano en Chrome.
- **Sin límite de subidas pendientes por usuario**; el limpiador acota el daño (`docs/v2.md`).

## Lo que encontró el arnés

Antes de arreglar el cliente, el arnés corrió con tres semillas. Dos pasaron y **una falló**:

| Semilla | Faltaban antes del resync final | Violaciones (1/2/3/4) |
|---|---|---|
| 42 | 0 | 0/0/0/0 |
| 1 | 0 | 0/0/0/0 |
| 2 | 22 | **22/0/287/0** |

El cliente 4 no tenía los `seq` 354 a 375 (22 mensajes seguidos) y la resincronización final
tampoco los trajo. Las invariantes 1 y 3 lo reportaron (la 3 cuenta cada posición desplazada
por los mensajes que faltan); el servidor tenía los 640.

**Causa:** el cliente pedía `after_seq = el mayor seq que tengo`. Al reconectar, un mensaje en
vivo (el 376) llegó antes de que corriera la resincronización; desde ese momento "el mayor
seq" era 376 y pedir "lo posterior al 376" ya no podía devolver el 354 al 375. El hueco
quedaba para siempre. El frontend tenía la misma debilidad (el cursor del resync al
suscribirse y el del botón «Buscar nuevos»): el pedido que detecta el hueco usa el `seq` de
antes del mensaje, pero si ese pedido falla, nada más lo repara.

**Arreglo:** el cursor de resincronización es el **final de la racha completa**, no el mayor
`seq`: el mayor `n` tal que se tienen 1..n (`ClientLog.contiguousSeq` en el arnés,
`contiguousSeqOf` en `frontend/lib/messages.ts`). Pedir desde ahí llena cualquier hueco por
debajo del último mensaje. Con el arreglo, la semilla 2 pasa (tabla de arriba).

Una advertencia honesta: la falla depende de cómo se intercalan los hilos, así que la
semilla 2 no es una reproducción exacta, y que ahora pase no prueba el arreglo por sí sola.
Lo que lo respalda es el análisis de la causa, la prueba unitaria `contiguousSeqStopsAtTheFirstHole`
y las seis corridas limpias.

## Comprobado que los tests muerden

Un test que nunca falla no demuestra nada, así que cada garantía del backend se rompió a
propósito y se corrió su test. Las cuatro roturas hicieron fallar lo que debían (todas
compilan; el código se restauró con `git checkout` después de cada una):

| Rotura | Test que la detecta | Resultado |
|---|---|---|
| Publicar antes del commit (`@EventListener` en vez de `AFTER_COMMIT`) | `PublishAfterCommitIntegrationTest` | 2 de 4 fallan: `aRolledBackTransactionPublishesNothing` y `aCommittedTransactionPublishesAfterTheCommit` |
| Quitar el chequeo de `AccessPolicy` en `SUBSCRIBE` | `StompSubscribeIntegrationTest` | 3 de 9 fallan (extraño, canal inexistente, privado ajeno) |
| Expulsar sin cerrar los sockets | `KickClosesSocketsIntegrationTest` | 2 de 3 fallan (misma instancia y otra instancia) |
| Permitir `SEND` | `StompSendIntegrationTest` | 3 de 3 fallan |

Lo mismo con las piezas del hito 5 (29/10, también restauradas con `git checkout`):

| Rotura | Test que la detecta | Resultado |
|---|---|---|
| Token bucket ingenuo: `GET` del contador y `SET` después, en vez del script Lua | `TokenBucketConcurrencyIntegrationTest` (100 solicitudes, capacidad 20) | falla: pasan **100 en vez de 20** |
| El cierre del socket no borra la presencia (`SessionRegistry.closed` no avisa a los oyentes) | `PresenceTrackingIntegrationTest` y `SessionRegistryTest` | fallan 5 de 6 y 3 de 10: el usuario sigue en línea tras cerrar la última pestaña, tras una expulsión y desde la otra instancia |
| El destino `.typing` se salta `AccessPolicy` en `SUBSCRIBE` | `StompAuthorizationMatrixTest` | fallan 7 celdas (miembro de otro servidor, expulsado y member en el privado ajeno, que debían dar 404) |

Del lado del arnés, lo que más se acerca a "quitar la resincronización" es la corrida de la
semilla 2 antes del arreglo (sección anterior): con un cursor de resincronización
incorrecto, las invariantes 1 y 3 fallaron. No se probó quitar la resincronización por
completo.

## Lo que esto no demuestra

- **Una sola máquina.** Las dos instancias, Postgres, RabbitMQ y los 8 clientes comparten el
  mismo equipo. No hay red real entre ellos ni pérdida de paquetes.
- **El reinicio es ordenado.** `docker compose restart` manda SIGTERM y Spring cierra con
  gracia. No se probó `kill -9`, que puede dejar un mensaje confirmado en la base sin avisar
  en vivo (el caso que ADR 0003 deja a cargo de la resincronización). Queda pendiente.
- **RabbitMQ y Postgres no se reinician.** Solo cae una instancia del backend.
- **Los clientes son los del arnés**, que copian la lógica del frontend, no el navegador. La
  prueba con Playwright y dos navegadores es del hito 9.
- **No mide latencia ni rendimiento** (DESIGN.md §8.5): eso también es del hito 9.
- **Una resincronización final siempre ayuda.** Por eso el informe separa lo que llegó en
  vivo, lo que trajo la resincronización y cuántos mensajes faltaban antes de la final.

Y, para las notificaciones del hito 6:

- **El SIGKILL real es solo del worker.** Postgres y RabbitMQ no se reinician durante la corrida
  de menciones, y a un backend (donde corre el publicador) no se le manda SIGKILL: el publicador
  solo se probó con caídas simuladas justo después de publicar y con dos publicadores a la vez.
- **Las caídas del test grande son excepciones, no procesos que desaparecen.** Para RabbitMQ
  equivalen a un mensaje sin confirmar, que es lo que importa para la deduplicación, pero no
  prueban lo que pasa con el estado en memoria de un proceso muerto (el worker no guarda ninguno).
- **No mide la latencia de las notificaciones** (DESIGN.md §8.5, hito 9). La interfaz las consulta
  cada 20 s, así que se ven con ese retraso más lo que tarde el publicador y el worker.
- **Un mensaje que mata el proceso en bucle** no cuenta como intento y no llegaría a la cola de
  mensajes muertos (ADR 0013); queda en `docs/v2.md`.
