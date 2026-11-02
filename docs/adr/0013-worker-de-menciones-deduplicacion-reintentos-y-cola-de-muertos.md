# ADR 0013 · Worker de menciones: deduplicación en la base, reintentos con colas de espera y cola de muertos

**Estado:** aceptada · 2026-11-02 · Complementa la ADR 0004, la ADR 0012 y DESIGN.md §5 y §8.3

## Contexto
El worker recibe los eventos de mención **al menos una vez** (ADR 0012) y tiene que poder morir en
cualquier momento sin que una mención se pierda ni se notifique dos veces (DESIGN.md §8.3). Hay
tres problemas:

- **Repetidos.** Un evento llega dos veces si el publicador murió entre publicar y marcar, o si
  el worker murió después de guardar la notificación y antes de confirmar el mensaje.
- **Fallos.** Algunos se arreglan esperando (la base no responde) y otros no (el evento es
  ilegible, o el mensaje ya no existe). Reintentar de inmediato y para siempre es un bucle.
- **Que sobreviva a su propia muerte.** Dormir dentro del hilo para esperar un reintento no
  sirve si el proceso muere: se pierde la espera.

## Decisión
1. **El mismo jar**, con `app.worker.enabled`. En el cluster es un servicio `worker` aparte (sin
   publicar la outbox y sin puertos); los backends corren con el worker apagado. Se puede matar
   con `docker compose kill worker` sin tocar a los backends.
2. **La deduplicación es una restricción de la base:** `UNIQUE (message_id, user_id)` en
   `notifications` y `INSERT … ON CONFLICT DO NOTHING`, en **una sola sentencia**. Procesar un
   evento es seguro de repetir y de correr a la vez en varios workers. El mensaje se confirma
   (`ack`) cuando el listener vuelve, es decir, después del commit; dos consumidores por proceso.
3. **Reintentos con colas de espera**, no en el hilo: ante un fallo transitorio el worker pasa el
   evento a `mentions.wait.<ms>`, una cola cuyo TTL lo devuelve a `mentions` al vencer. El
   intento *k* espera la *k*-ésima espera de `app.worker.retry-delays` (por defecto 2 s, 10 s y
   1 min: cuatro intentos). El número de intento viaja en el header `x-attempt`.
4. **Es una cola por espera, y no un TTL por mensaje.** RabbitMQ solo vence lo que está al frente
   de la cola: con TTL distintos mezclados, uno largo bloquea a los cortos. El nombre de cada cola
   lleva sus milisegundos, para que cambiar las esperas cree colas nuevas en vez de chocar con
   las ya declaradas (`PRECONDITION_FAILED`).
5. **Cola de mensajes muertos** `mentions.dlq`: ahí va lo que agotó sus intentos o no tiene
   arreglo (JSON ilegible; `DataIntegrityViolationException`, porque el mensaje o la persona ya no
   existen), sin esperas, con `x-attempt`, `x-error` y `x-failed-at`. Nadie lee de ella; se
   inspecciona en la UI de RabbitMQ.
6. **Traspaso confirmado antes del `ack`.** Para pasar un evento a una cola de espera o a la de
   muertos se usa `ConfirmedPublisher` (ADR 0012). Si RabbitMQ no confirma, el listener lanza una
   excepción y el mensaje original vuelve a la cola: nunca se confirma un evento sin haberlo
   dejado a salvo en otro lado.
7. Contadores de Micrometer: `mentions.notified`, `mentions.duplicates`, `mentions.retried`,
   `mentions.dead`.

## Alternativas descartadas
- **Reintento en memoria** (`spring-retry` o el `RetryTemplate` de Spring Framework 7): la espera
  vive en el hilo y se pierde si el worker muere.
- **`reject` sin reencolar y un DLX:** el broker mueve el mensaje de forma atómica, pero no deja
  elegir la espera según el intento; todos esperarían lo mismo.
- **Deduplicar en memoria o con una clave en Redis:** no sobrevive a un reinicio ni cubre a varios
  workers, y Redis puede perderse (DESIGN.md §6). La base ya es la fuente de verdad.
- **Una tabla de eventos procesados** (patrón *inbox*): innecesaria mientras el efecto sea una
  fila con clave única. Haría falta con un efecto que no se pueda repetir, como mandar un correo.
- **«Preguntar si existe y luego crear»:** deja pasar dos cuando dos workers preguntan a la vez.

## Consecuencias
- **«Exactamente una vez» es del efecto, no de la entrega:** se logra con *al menos una vez* más
  una inserción idempotente. El mismo evento puede llegar varias veces; la notificación es una.
- Pasar el evento a otra cola y confirmar el original **no es atómico**: si el proceso muere en
  medio, el evento queda dos veces. Lo absorbe la misma deduplicación.
- **Límite conocido:** un mensaje que *mata* el proceso en bucle no cuenta como intento (no es una
  excepción del listener). Lo cubriría el `delivery-limit` de una cola *quorum*; queda en
  `docs/v2.md`.
- No hay orden entre notificaciones, y no hace falta.
- Lo prueban `MentionWorkerIntegrationTest` (5), `MentionWorkerRetryIntegrationTest` (5: falla dos
  veces y entra tras las esperas, falla siempre y va a la cola de muertos con el motivo, evento
  ilegible, mensaje inexistente, traspaso sin confirmar), `NotificationDedupeConcurrencyIntegrationTest`
  (160 llamadas a la vez sobre 20 pares: exactamente 20 notificaciones) y
  `WorkerInterruptedIntegrationTest` (§8.3: tres semillas, unas 400 menciones cada una, con el
  worker muriendo antes de procesar y después de guardar, la conexión cortada, el worker parado
  y arrancado, y dos publicadores que mueren tras publicar; ver `docs/DELIVERY.md`).
- **Hallazgo al romperlo a propósito (02/11):** con una deduplicación ingenua (consultar y luego
  insertar, sin la restricción), `WorkerInterruptedIntegrationTest` **pasó** las tres semillas.
  Tras una caída el evento se reentrega *después* de que el primer intento terminó, y hasta la
  versión ingenua lo atrapa. Lo que la rompe es la carrera entre dos workers con el mismo evento,
  y eso lo prueba `NotificationDedupeConcurrencyIntegrationTest`: con la versión ingenua crea 72
  notificaciones en vez de 20. Por eso son dos pruebas y no una.
