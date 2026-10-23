# ADR 0001 · Número de secuencia por canal

**Estado:** aceptada · 2026-10-23 · Detalle en DESIGN.md §3.2

## Contexto
Ordenar mensajes por la hora del cliente falla porque los relojes no coinciden, y sin un
número contiguo el cliente no puede saber que le falta algo.

## Decisión
Cada canal tiene `channels.last_seq`. Al enviar, en la misma transacción:
`UPDATE channels SET last_seq = last_seq + 1 ... RETURNING last_seq` y después el `INSERT`
del mensaje con ese `seq`. `UNIQUE (channel_id, seq)` y `UNIQUE (author_id, client_msg_id)`.

## Consecuencias
- El orden es el mismo para todos y los huecos se detectan (N+2 sin N+1).
- Resincronizar es `after_seq=N`; los no leídos son una resta.
- Los envíos a un mismo canal se serializan sobre la fila del canal. Se acepta por el
  volumen de un chat y se mide en la §8.5.
