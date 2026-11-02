# ADR 0011 · Menciones: `@username` único, resueltas al enviar, y notificaciones que se consultan

**Estado:** aceptada · 2026-11-02 · Complementa DESIGN.md §5 y la ADR 0004

## Contexto
Un `@usuario` en un mensaje tiene que acabar en una notificación (DESIGN.md §5). Cuatro cosas
hay que decidir:

- **A quién se refiere.** Hoy un usuario tiene correo y `display_name`, y el nombre visible no
  es único ni tiene reglas (repetidos, espacios): `@Ana` podría ser cualquiera de dos personas.
- **A quién se le avisa.** Avisar a alguien que no ve un canal privado le revelaría que el
  mensaje existe, justo lo que la matriz de autorización se esfuerza en no hacer.
- **Los permisos cambian después.** Alguien puede ser expulsado, sacado de un privado o dejar de
  ser admin *después* de que lo mencionaran. Su notificación sigue existiendo.
- **Cómo se entera la interfaz.** En vivo, o consultando.

## Decisión
1. **`users.username`**: único sin distinguir mayúsculas (índice sobre `lower(username)`), de 3 a
   20 letras, números o guion bajo. Se pide al registrarse (409 si está tomado, también en la
   carrera entre dos registros). `everyone`, `here` y `channel` están reservados para que
   `@everyone` quede libre para la v2. La migración V4 rellena a los usuarios que ya existían con
   la parte local de su correo, y le agrega el id si queda corta o choca.
2. **Qué cuenta como mención** (`MentionParser`): un `@` que no va pegado a una letra, un número,
   un guion bajo ni otro `@` (así `ana@correo.com` no menciona a nadie), seguido de un handle que
   no sigue con más caracteres de palabra (`@betoñ` no menciona a `beto`). Se leen hasta **10
   handles distintos** por mensaje: acota las consultas de acceso que un solo mensaje puede
   provocar. El frontend usa la misma regla para resaltar; los vectores de `MentionParserTest`
   valen para las dos.
3. **Se resuelven al enviar**, dentro de la transacción del mensaje y *antes* de reservar el
   `seq` (son solo lecturas; así no alargan el bloqueo de la fila del canal). Una persona se
   notifica solo si existe, no es quien escribe y `AccessPolicy.can(READ)` sobre ese canal le
   dice que sí. `can` envuelve a `requireChannel`: la regla es la de siempre, no una copia. Sin
   `@` en el texto no hay ninguna consulta extra. Quien escribe no se entera de a quiénes
   alcanzó, así que no puede usar una mención para averiguar quién está en un privado.
4. **Un evento de la outbox por (mensaje, destinatario)**, escrito en la misma transacción
   (ADR 0004, ADR 0012). Si algo falla, se revierten el mensaje, su `seq` y sus eventos.
5. **Las notificaciones se consultan, no se avisan.** `GET /notifications?limit=` (20 por
   defecto, 100 como máximo) devuelve `{unreadCount, items}`; la interfaz lo pide cada 20 s y al
   volver a la pestaña, igual que la presencia (ADR 0008). No hay destino STOMP nuevo.
6. **La lista se filtra al leer**, contra el acceso de ahora: toma los canales donde la persona
   tiene notificaciones, se queda con los que `AccessPolicy` todavía le deja leer y consulta solo
   esos; la cuenta de no leídas sale del mismo conjunto. No se borra nada al expulsar o al
   cambiar un permiso: la notificación simplemente deja de verse.
7. **Marcar leída:** `PUT /notifications/{id}/read` y `POST /notifications/read-all`. Toda
   consulta va atada a `user_id`; la notificación de otra persona responde 404, igual que una que
   no existe.

## Alternativas descartadas
- **Mencionar por `display_name`:** ambiguo; podría notificar a la persona equivocada o a varias.
- **Que el cliente mande los ids** (`mentions: [id]` junto al texto): sin ambigüedad y sin columna
  nueva, pero obliga a guardar las menciones aparte para resaltarlas al recargar el historial, y
  quien use la API solo con texto no puede mencionar.
- **Aviso en vivo por STOMP, un destino por usuario:** más inmediato, pero exige un socket por
  sesión (hoy el WebSocket solo se abre en la página de un canal, ADR 0008), un destino más en la
  matriz STOMP y su regla en `SUBSCRIBE`. Se puede añadir encima de la consulta más adelante.
- **Borrar las notificaciones al expulsar, al sacar de un privado o al degradar a un admin:**
  habría que enganchar cada camino por el que se pierde acceso, y olvidar uno filtraría. Filtrar
  al leer los cubre todos, también los que se agreguen.

## Consecuencias
- Las notificaciones llegan con a lo sumo ~20 s de retraso en la interfaz (más el del worker).
- Si alguien vuelve a un servidor con una invitación nueva (no hay bans), las notificaciones de
  antes de su expulsión reaparecen: eran suyas y los canales vuelven a ser visibles.
- La lista muestra un resumen de hasta 120 caracteres del mensaje, de una sola línea, y solo de
  canales que la persona puede leer ahora.
- Registrarse ahora pide un `username` (cambió el formulario, la API y el arnés). La migración V4
  se probó con datos reales: los 161 usuarios que ya había en la base de desarrollo, de los
  hitos anteriores, recibieron su `username` sin choques.
- Lo prueban `MentionParserTest` (vectores), `MentionOutboxIntegrationTest` (a quién se notifica y
  a quién no, y que una falla de la outbox revierte mensaje, `seq` y eventos),
  `NotificationAccessIntegrationTest` (expulsado, sacado de un privado, admin degradado) y tres
  filas nuevas en la matriz HTTP (28 a 31 filas, 186 celdas).
- Comprobado que el test muerde (02/11): si los eventos se escriben *después* del commit del
  mensaje, falla la prueba de atomicidad (queda 1 mensaje donde debía haber 0).
