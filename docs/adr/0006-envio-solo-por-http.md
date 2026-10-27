# ADR 0006 · Los mensajes se escriben solo por HTTP; STOMP solo recibe

**Estado:** aceptada · 2026-10-27 · Complementa las ADR 0002, 0003 y 0005 y DESIGN.md §3.2 y §4

## Contexto
DESIGN.md §4 pide autorizar `SUBSCRIBE` y `SEND`. Con el relay a RabbitMQ (ADR 0002), un
frame `SEND` a `/topic/channel.N` no pasa por ninguna lógica nuestra: el relay lo reenvía a
RabbitMQ y llega a todos los suscriptores. Cualquiera que pueda abrir un WebSocket podría
publicar un mensaje falso en cualquier canal, con el `seq` y el autor que quisiera, sin que
exista en la base. Es el mismo hueco que la sección 4 llama "WebSocket sin revisar", pero
del lado de la escritura.

El envío ya tiene un camino correcto: `POST /channels/{id}/messages` asigna el `seq`,
reconoce los reintentos (`client_msg_id`, ADR 0005) y pasa por `AccessPolicy`. Un segundo
camino por STOMP duplicaría esa lógica y el rastro de errores.

## Decisión
- Los mensajes se escriben **solo** por `POST /channels/{id}/messages`.
- STOMP solo sirve para recibir. `StompSecurityInterceptor` rechaza **todo** `SEND` con 403,
  sin mirar el destino, y rechaza cualquier comando que no sea `CONNECT`, `SUBSCRIBE`,
  `UNSUBSCRIBE` o `DISCONNECT`. Un frame que no se sabe leer también se rechaza.
- `SUBSCRIBE` solo acepta `/topic/channel.{id}` (id numérico, sin comodines) y pasa por
  `AccessPolicy.requireChannel(READ)` en cada suscripción.
- El servidor publica el aviso en `/topic/channel.{id}` él mismo, después del commit
  (ADR 0003).

## Alternativas descartadas
- **`@MessageMapping` que llame a `MessageService.send`:** funcionaría, pero hay que
  devolver el acuse por una cola de usuario del relay, y la matriz STOMP tendría filas de
  `SEND` permitidas. Más superficie para el mismo resultado.
- **Permitir `SEND` a `/topic/...` y confiar en el cliente:** es el hueco descrito arriba.
- **Dejar `SEND` abierto a destinos `/app/...` "por si acaso":** un destino que no existe
  no se justifica; si aparece uno, debe llegar con su fila en la matriz.

## Consecuencias
- Un solo camino de escritura: lo que pasa por `MessageService.send` es lo único que se
  publica en vivo.
- `StompAuthorizationMatrixTest` prueba `SEND` a un tópico de canal (público y privado) y a
  un destino de aplicación contra los seis tipos de usuario; otro test falla si alguien
  agrega un `@MessageMapping` sin ponerlo en la matriz.
- `StompSendIntegrationTest` prueba el ataque real: un suscriptor publica directo en
  `/topic/channel.N` y los demás no reciben nada.
- Tras un frame `ERROR`, Spring cierra el WebSocket. El cliente reconecta y, como siempre,
  se resincroniza por `seq`.
- Si más adelante se quisiera enviar por STOMP (por ejemplo, para bajar la latencia), esta
  ADR se reemplaza y la matriz gana filas; no se abre `SEND` sin ellas.
