# ADR 0004 · Tabla outbox para las menciones

**Estado:** aceptada · 2026-10-23 · Detalle en DESIGN.md §5

## Contexto
Si el mensaje se guarda y el evento de mención se pierde antes de llegar a la cola, la
mención nunca se notifica.

## Decisión
Escribir el evento en `outbox` en la misma transacción que el mensaje. Un publicador lo
lee y lo envía a RabbitMQ; el worker deduplica por `(message_id, user_id)`, reintenta con
espera creciente y manda los fallos a una cola de mensajes muertos.

## Consecuencias
- Entrega al menos una vez; el consumidor tiene que ser idempotente.
- Se prueba matando el worker en momentos aleatorios (DESIGN.md §8.3).
