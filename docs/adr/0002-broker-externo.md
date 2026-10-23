# ADR 0002 · Broker STOMP externo (RabbitMQ)

**Estado:** aceptada · 2026-10-23 · Detalle en DESIGN.md §3.2 y §6

## Contexto
El broker simple en memoria de Spring no sirve con varias instancias: quien está conectado
a la B no recibe lo que se publicó en la A.

## Decisión
`enableStompBrokerRelay` hacia RabbitMQ (plugin STOMP). Cualquier instancia publica y
RabbitMQ entrega a todas. Dos instancias obligatorias desde el hito 4.

## Consecuencias
- Las suscripciones viven en el broker, así que el backend no puede quitar una sola; al
  expulsar a alguien se cierran sus sesiones WebSocket (DESIGN.md §4).
- RabbitMQ puede perderse sin perder mensajes: PostgreSQL es la fuente de verdad y los
  clientes se resincronizan por `seq`.
