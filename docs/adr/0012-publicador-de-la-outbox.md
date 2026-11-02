# ADR 0012 · Publicador de la outbox: lotes con `SKIP LOCKED` y marca solo con confirmación

**Estado:** aceptada · 2026-11-02 · Complementa la ADR 0004 y DESIGN.md §5

## Contexto
La ADR 0004 decidió escribir el evento en una tabla `outbox` en la misma transacción que el
mensaje. Falta decir cómo se lleva a RabbitMQ sin perderlo y sin que dos instancias se pisen:

- Publicar y marcar son dos sistemas (RabbitMQ y Postgres): no hay transacción que los cubra.
- Hay dos instancias del backend, y las dos tienen un publicador.
- Un `send` que no lanza no prueba que el broker lo tenga; y un exchange sin cola enlazada
  confirma y descarta en silencio.
- El `id` de la outbox se asigna *antes* del commit.

## Decisión
1. **Tabla `outbox`** (V5): `id`, `event_type`, `payload` JSONB, `created_at`, `published_at`, con
   un índice parcial sobre lo pendiente (`WHERE published_at IS NULL`).
2. **Una vuelta = una transacción** (`OutboxPublisher.drainOnce`): toma hasta 100 filas con
   `SELECT … WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`, las
   publica, espera las confirmaciones y recién entonces marca `published_at` **solo las
   confirmadas**. Corre en cada instancia cada 200 ms (más seguido si el lote vino lleno).
   `SKIP LOCKED` hace que el segundo publicador se salte lo que el primero tiene tomado, sin
   esperarlo y sin llevarse lo mismo.
3. **Lo pendiente se busca por `published_at IS NULL`, no por «el id siguiente al último visto».**
   Una fila de id menor puede confirmar después de otra de id mayor; un cursor por id se la
   saltaría para siempre. Los eventos no tienen orden entre sí, y no hace falta.
4. **Confirmaciones y `mandatory`** (`ConfirmedPublisher`, con su propio `RabbitTemplate`, que no
   es un bean: uno de ese tipo apagaría el que Spring Boot configura para el canal de control).
   Un evento cuenta como publicado solo si el broker lo confirmó *y* lo ruteó a una cola; si
   vuelve devuelto o no se confirma en 5 s, sigue pendiente. El worker usa la misma clase para
   pasar un evento a otra cola (ADR 0013).
5. **Exchange `chat.events`** (direct) con el tipo del evento como clave de ruteo. La topología
   (exchange, cola `mentions`, colas de espera, cola de mensajes muertos) la declaran **todos los
   procesos**: si el publicador corriera antes de que exista la cola, el evento se perdería.
6. **Si RabbitMQ no responde**, la vuelta se revierte y los eventos siguen pendientes; el aviso
   al log sale a lo sumo cada 10 s. Enviar mensajes no se afecta: solo escribe en Postgres.
7. Se apaga con `app.outbox.publisher.enabled=false` (el proceso del worker no publica). El
   planificador de Spring tiene 2 hilos, para que esperar confirmaciones no retrase el latido de
   la presencia.

## Alternativas descartadas
- **Publicar en `AFTER_COMMIT` directo:** es el problema que la outbox resuelve; si el proceso
  muere entre el commit y el publicar, el evento se pierde.
- **Marcar antes de publicar, en otra transacción:** si muere en medio, el evento se pierde.
  Marcar y publicar en la *misma* transacción no se diferencia en una prueba (el rollback lo
  deshace), por eso lo que se rompió a propósito fue marcar sin mirar las confirmaciones.
- **Un solo publicador con un lock global** (advisory lock): serializa a las instancias sin
  necesidad; `SKIP LOCKED` las deja trabajar sobre lotes distintos.
- **CDC (Debezium) o `LISTEN/NOTIFY`:** más piezas para un volumen que no lo pide. `LISTEN/NOTIFY`
  podría despertar al publicador al instante; hoy se sondea.

## Consecuencias
- **Al menos una vez.** Si el proceso muere después de publicar y antes de marcar, la transacción
  se pierde y el lote sale otra vez. El consumidor tiene que deduplicar (ADR 0013).
- La fila queda bloqueada, y una conexión del pool tomada, mientras se espera la confirmación
  (hasta 5 s). A este volumen es aceptable; con RabbitMQ lento, el pool lo notaría.
- Latencia: ~200 ms de sondeo más el viaje a RabbitMQ.
- Las filas ya publicadas no se borran (queda en `docs/v2.md`).
- Lo prueba `OutboxPublisherIntegrationTest` (8 pruebas): publica y marca; sin pendientes no hace
  nada; si el broker no confirma, sigue pendiente y sale en una vuelta posterior; solo se marca
  lo confirmado; una falla del transporte no marca nada; una caída entre publicar y marcar
  republica (cada evento queda dos veces en la cola); un evento sin cola que lo reciba vuelve
  devuelto y sigue pendiente; y **dos publicadores a la vez sobre 500 filas** publican cada una
  exactamente una vez.
- Comprobado que el test muerde (02/11): si el publicador marca todo el lote sin mirar lo que
  el broker confirmó, fallan 3 de esas 8 pruebas (el broker que no confirma nada, el evento sin
  cola y el lote confirmado a medias).
