# ADR 0007 · Cerrar los sockets al expulsar, con un exchange fanout entre instancias

**Estado:** aceptada · 2026-10-27 · Complementa la ADR 0002 y DESIGN.md §4

## Contexto
Con el relay (ADR 0002) las suscripciones viven en RabbitMQ y el backend no puede quitar
una sola. Si un admin expulsa a alguien, su suscripción abierta seguiría recibiendo
mensajes hasta que recargue. Además hay dos instancias: el usuario puede tener su socket en
la B mientras la expulsión la atiende la A.

## Decisión
1. `ServerService.kick` publica `UserKicked` dentro de la transacción de la expulsión.
2. `KickBroadcaster` lo escucha con `@TransactionalEventListener(AFTER_COMMIT)` (ADR 0003):
   si la expulsión se revierte, no se corta a nadie.
3. Primero cierra los sockets del usuario **en esta instancia** (`SessionRegistry`, con
   `CloseStatus.POLICY_VIOLATION`), sin depender de nadie más.
4. Después publica `kicked:{userId}:{instanceId}` en el exchange fanout `chat.control` de
   RabbitMQ (AMQP). Cada instancia tiene su propia cola anónima (exclusiva, nombre único,
   se borra al apagarse) y, al recibirlo, cierra los sockets locales de ese usuario.
5. Cada instancia **ignora su propio aviso**: ya cerró lo suyo, y reaccionar al eco cortaría
   también el socket nuevo con el que el usuario reconectó en esos milisegundos.
6. Al reconectar, `SUBSCRIBE` pasa por `AccessPolicy`, que lee la base, y le da 404 aunque su
   JWT siga vigente.

El registro de sockets se arma con un `WebSocketHandlerDecorator` (abre y cierra) y un
`bind` en el `CONNECT` autenticado (a quién pertenece).

## Alternativas descartadas
- **Cerrar solo en la instancia que atiende la expulsión:** deja vivo el socket del usuario
  si estaba en la otra.
- **Comprobar la membresía en cada mensaje entregado:** no se puede; el relay entrega
  desde RabbitMQ sin pasar por el backend.
- **Una lista de usuarios expulsados en Redis que el interceptor consulte:** Redis puede
  perderse (DESIGN.md §6) y, de todos modos, no corta una suscripción ya hecha.
- **Cerrar solo los sockets suscritos a canales de ese servidor:** exigiría rastrear
  suscripciones por sesión. Se cierran todos los del usuario; el cliente reconecta y
  vuelve a suscribirse a lo que todavía puede ver.

## Consecuencias
- Si RabbitMQ no está, el aviso a otras instancias no sale (se registra una advertencia).
  Tampoco hay entrega en vivo en ese caso, y `SUBSCRIBE` sigue protegido por `AccessPolicy`.
- Un expulsado que reconecta durante la ventana entre el commit y el aviso a otra instancia
  puede tener un socket abierto unos milisegundos más, pero no puede suscribirse a nada.
- Lo prueban `KickClosesSocketsIntegrationTest` (misma instancia, otra instancia y
  expulsión revertida), `ControlChannelIntegrationTest` y
  `KickedUserCannotResubscribeIntegrationTest`.
- Un expulsado puede volver con una invitación vigente: no hay bans (docs/v2.md).
