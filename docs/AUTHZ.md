# Matriz de autorización

HTTP desde el hito 2 (mensajes en el hito 3); los destinos STOMP, en el hito 4 (DESIGN.md §8.2).

## Cómo se decide

Toda decisión de acceso pasa por `AccessPolicy` (`backend/.../access/AccessPolicy.java`).
Ningún controlador ni servicio decide permisos por su cuenta. Lee siempre de la base de
datos y nunca del JWT, por eso quien es expulsado queda fuera en la solicitud siguiente
aunque su token siga vigente.

| Código | Cuándo |
|---|---|
| **401** | No hay token válido |
| **404** | El usuario no puede ver el recurso: no es miembro del servidor, o el canal es privado y no está en él. No se distingue de que no exista, para no confirmar que existe |
| **403** | El usuario ve el recurso pero su rol no alcanza para la acción |

### Roles

- **member:** ve el servidor, sus miembros y los canales públicos. En un privado solo
  entra si está en `channel_members`. Lee y escribe mensajes en todo canal que ve: no
  hay canales de solo lectura.
- **admin:** además crea canales e invitaciones, administra los miembros de un privado,
  expulsa members y ve todos los privados.
- **owner:** además cambia roles. Es intocable: nadie lo expulsa ni le cambia el rol.

### Reglas sobre otro miembro (expulsar, cambiar rol)

- Nadie actúa sobre sí mismo.
- Un admin solo expulsa members, no a otro admin.
- Solo el owner cambia roles, y solo a `admin` o `member`.
- Al expulsar se borran también sus filas de `channel_members` en ese servidor.

### Decisión consciente: sin bans

Un expulsado puede volver con una invitación vigente (`POST /invites/{code}/accept`
devuelve 200). No existe una lista de bloqueados; queda en `docs/v2.md`.

## Matriz HTTP

Una fila por endpoint y caso, una columna por tipo de usuario. **Esta tabla la verifica
`AuthorizationMatrixTest`:** cada celda es un test que hace la solicitud real contra
Postgres, y otro test falla si existe un endpoint que no está aquí. Para regenerarla
después de cambiar la matriz:
`./mvnw test -Dtest=AuthorizationMatrixTest -Dauthz.update=true`.

"Expulsado hace un segundo" usa un token emitido antes de la expulsión, que sigue siendo
válido.

<!-- matrix:start -->
| Endpoint | Caso | owner | admin | member | miembro de otro servidor | sin sesión | expulsado hace un segundo |
|---|---|---|---|---|---|---|---|
| `GET /servers` | — | 200 | 200 | 200 | 200 | 401 | 200 |
| `POST /servers` | — | 201 | 201 | 201 | 201 | 401 | 201 |
| `GET /servers/{serverId}` | — | 200 | 200 | 200 | 404 | 401 | 404 |
| `GET /servers/{serverId}/members` | — | 200 | 200 | 200 | 404 | 401 | 404 |
| `GET /servers/{serverId}/presence` | — | 200 | 200 | 200 | 404 | 401 | 404 |
| `PUT /servers/{serverId}/members/{userId}/role` | cambiar a un member | 200 | 403 | 403 | 404 | 401 | 404 |
| `DELETE /servers/{serverId}/members/{userId}` | expulsar a un member | 204 | 204 | 403 | 404 | 401 | 404 |
| `GET /servers/{serverId}/channels` | — | 200 | 200 | 200 | 404 | 401 | 404 |
| `POST /servers/{serverId}/channels` | — | 201 | 201 | 403 | 404 | 401 | 404 |
| `GET /channels/{channelId}` | público | 200 | 200 | 200 | 404 | 401 | 404 |
| `GET /channels/{channelId}` | privado, member no está | 200 | 200 | 404 | 404 | 401 | 404 |
| `GET /channels/{channelId}` | privado, member sí está | 200 | 200 | 200 | 404 | 401 | 404 |
| `POST /channels/{channelId}/members` | privado, member no está | 204 | 204 | 404 | 404 | 401 | 404 |
| `POST /channels/{channelId}/members` | privado, member sí está | 204 | 204 | 403 | 404 | 401 | 404 |
| `DELETE /channels/{channelId}/members/{userId}` | privado, member no está | 204 | 204 | 404 | 404 | 401 | 404 |
| `DELETE /channels/{channelId}/members/{userId}` | privado, member sí está | 204 | 204 | 403 | 404 | 401 | 404 |
| `POST /channels/{channelId}/messages` | público | 201 | 201 | 201 | 404 | 401 | 404 |
| `POST /channels/{channelId}/messages` | privado, member no está | 201 | 201 | 404 | 404 | 401 | 404 |
| `POST /channels/{channelId}/messages` | privado, member sí está | 201 | 201 | 201 | 404 | 401 | 404 |
| `GET /channels/{channelId}/messages` | público | 200 | 200 | 200 | 404 | 401 | 404 |
| `GET /channels/{channelId}/messages` | privado, member no está | 200 | 200 | 404 | 404 | 401 | 404 |
| `GET /channels/{channelId}/messages` | privado, member sí está | 200 | 200 | 200 | 404 | 401 | 404 |
| `POST /channels/{channelId}/typing` | público | 204 | 204 | 204 | 404 | 401 | 404 |
| `POST /channels/{channelId}/typing` | privado, member no está | 204 | 204 | 404 | 404 | 401 | 404 |
| `POST /channels/{channelId}/typing` | privado, member sí está | 204 | 204 | 204 | 404 | 401 | 404 |
| `POST /servers/{serverId}/invites` | — | 201 | 201 | 403 | 404 | 401 | 404 |
| `GET /invites/{code}` | vigente | 200 | 200 | 200 | 200 | 401 | 200 |
| `POST /invites/{code}/accept` | vigente | 200 | 200 | 200 | 200 | 401 | 200 |
<!-- matrix:end -->

## Matriz STOMP

Los mensajes se **escriben solo por HTTP** (ADR 0006): STOMP únicamente recibe. Por eso solo
hay dos comandos con fila: `SUBSCRIBE`, que pasa por `AccessPolicy` en cada suscripción, y
`SEND`, que siempre se rechaza (con el relay, un `SEND` a `/topic/channel.N` llegaría a los
suscriptores sin pasar por el `seq` ni por la autorización).

El WebSocket acepta el handshake sin token y el JWT va en el frame `CONNECT`; sin token válido
el servidor responde un frame `ERROR` con **401**. Un `SUBSCRIBE` rechazado responde `ERROR`
con el mismo código que daría HTTP (**404** si no ve el canal) y el servidor cierra el socket.
`ok` significa que el servidor dejó pasar el comando.

"Expulsado hace un segundo" abre el WebSocket con un token emitido antes de la expulsión, que
sigue siendo válido: puede conectar, pero `AccessPolicy` lee la base y le niega cada
suscripción. Además, al expulsar se cierran sus sockets abiertos en todas las instancias
(ADR 0007).

Esta tabla la verifica `StompAuthorizationMatrixTest` (un test por celda, contra un
WebSocket real) y otro test falla si aparece un destino de aplicación sin fila. Para
regenerarla: `./mvnw test -Dtest=StompAuthorizationMatrixTest -Dauthz.update=true`.

<!-- stomp-matrix:start -->
| Comando y destino | Caso | owner | admin | member | miembro de otro servidor | sin sesión | expulsado hace un segundo |
|---|---|---|---|---|---|---|---|
| `SUBSCRIBE /topic/channel.{channelId}` | público | ok | ok | ok | 404 | 401 | 404 |
| `SUBSCRIBE /topic/channel.{channelId}` | privado, member no está | ok | ok | 404 | 404 | 401 | 404 |
| `SUBSCRIBE /topic/channel.{channelId}` | privado, member sí está | ok | ok | ok | 404 | 401 | 404 |
| `SUBSCRIBE /topic/channel.{channelId}.typing` | público | ok | ok | ok | 404 | 401 | 404 |
| `SUBSCRIBE /topic/channel.{channelId}.typing` | privado, member no está | ok | ok | 404 | 404 | 401 | 404 |
| `SUBSCRIBE /topic/channel.{channelId}.typing` | privado, member sí está | ok | ok | ok | 404 | 401 | 404 |
| `SEND /topic/channel.{channelId}.typing` | público | 403 | 403 | 403 | 403 | 401 | 403 |
| `SUBSCRIBE /topic/channel.999999999` | canal que no existe | 404 | 404 | 404 | 404 | 401 | 404 |
| `SUBSCRIBE /topic/other` | destino que no es de un canal | 404 | 404 | 404 | 404 | 401 | 404 |
| `SEND /topic/channel.{channelId}` | público | 403 | 403 | 403 | 403 | 401 | 403 |
| `SEND /topic/channel.{channelId}` | privado, member no está | 403 | 403 | 403 | 403 | 401 | 403 |
| `SEND /app/channels/1/messages` | destino de aplicación | 403 | 403 | 403 | 403 | 401 | 403 |
<!-- stomp-matrix:end -->
