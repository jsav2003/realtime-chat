# ADR 0003 · Publicar después del commit

**Estado:** aceptada · 2026-10-23 · Detalle en DESIGN.md §3.2

## Contexto
Avisar por WebSocket antes de que la transacción confirme puede mostrar un mensaje que
luego no existe.

## Decisión
El aviso se emite con `@TransactionalEventListener(phase = AFTER_COMMIT)`.

## Consecuencias
- Nunca se publica algo que no se guardó.
- Si el servidor cae entre el commit y la publicación, nadie lo recibe en vivo; la
  resincronización por `seq` lo recupera.
