# ADR 0005 · Envío idempotente sin huecos en el seq

**Estado:** aceptada · 2026-10-26 · Complementa la ADR 0001 y DESIGN.md §3.2

## Contexto
El `seq` se reserva con `UPDATE channels SET last_seq = last_seq + 1 ... RETURNING` y el
reintento se reconoce por `UNIQUE (author_id, client_msg_id)`. Si dos reintentos del mismo
mensaje llegan a la vez, los dos pasan la búsqueda de la clave antes de que el primero
confirme, y los dos reservan un `seq`. El segundo choca en el `INSERT`. Si su transacción
confirmara de todos modos, el canal quedaría con un `seq` reservado sin mensaje: un hueco
permanente, y los clientes lo pedirían por siempre creyendo que les falta algo.

## Decisión
`MessageService.send`, en una sola transacción (`TransactionTemplate`):

1. `AccessPolicy.requireChannel(WRITE)`.
2. Si la clave ya existe, devolver ese mensaje (reintento, HTTP 200).
3. Reservar el `seq` (el `UPDATE` toma el lock de la fila del canal).
4. `INSERT ... ON CONFLICT (author_id, client_msg_id) DO NOTHING RETURNING`.
5. Si el `INSERT` no devolvió fila, lanzar una excepción. **Revierte la transacción y con
   ella el `last_seq` reservado.** Fuera de la transacción se relee el mensaje que sí quedó
   guardado y se devuelve como reintento.

La misma clave en otro canal es un error del cliente: 409.

## Alternativas descartadas
- **`SELECT ... FOR UPDATE` de la clave antes de reservar:** no hay fila que bloquear
  cuando el mensaje todavía no existe.
- **Confirmar y aceptar el hueco:** rompe la regla de que un `seq` faltante significa un
  mensaje perdido (§3.2), que es lo que permite resincronizar.
- **`@Transactional` en el método:** el `catch` quedaría dentro de la transacción ya
  marcada para revertir; con `TransactionTemplate` queda claro dónde termina.

## Consecuencias
- `last_seq` siempre es igual al número de mensajes del canal: la secuencia es densa.
- Lo prueba `MessageSeqConcurrencyIntegrationTest`: diez reintentos simultáneos de un
  mensaje dejan un solo mensaje y `last_seq = 1`. Sin el paso 5 el test da `last_seq = 10`.
- El reintento que pierde la carrera espera el lock del canal antes de descubrirlo. Es el
  mismo costo de serializar envíos que ya acepta la ADR 0001.
