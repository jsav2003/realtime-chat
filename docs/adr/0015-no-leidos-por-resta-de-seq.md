# ADR 0015 · No leídos: un cursor por persona y canal, monótono y acotado, fuera del envío

**Estado:** aceptada · 2026-11-25 · Complementa DESIGN.md §3.3 y §7

## Contexto
DESIGN.md §3.3 ya fija la fórmula: `no_leídos = channels.last_seq − read_state.last_read_seq`. Es
exacta **porque el `seq` de un canal no tiene huecos** (ADR 0001 y 0005): cada número entre 1 y
`last_seq` es un mensaje, así que la resta cuenta mensajes sin recorrerlos. Quedaban abiertas tres
preguntas:

- **Quién mueve el cursor y cuándo.** Podría hacerlo el envío (lo propio cuenta como leído), el
  servidor al entregar por STOMP o el cliente al mostrar.
- **Qué pasa con el desorden.** Dos pestañas abiertas, o dos respuestas que llegan al revés, pueden
  mandar «leí hasta el 20» y luego «leí hasta el 10».
- **Qué ve quien pierde el acceso.** Un expulsado, o alguien sacado de un privado, tiene filas en
  `read_state` de canales que ya no puede leer.

## Decisión
1. **Tabla `read_state (user_id, channel_id, last_read_seq)`** (migración V9), con clave primaria
   compuesta y `CHECK (last_read_seq >= 0)`. **Sin fila = 0**: quien llega a un canal viejo ve todo
   como no leído (la interfaz muestra «99+»). Así no hay que crear filas al unirse a un servidor ni
   al crear un canal.
2. **`PUT /channels/{id}/read` `{"seq": N}` → 204**, para quien puede leer el canal
   (`AccessPolicy.requireChannel(READ)`; 404 al resto, igual que pedir sus mensajes). Es un solo
   `INSERT … SELECT … ON CONFLICT DO UPDATE`, sin lecturas previas:
   - **Acotado:** se guarda `least(N, channels.last_seq)`. Marcar 999 en un canal de 2 mensajes deja
     2, y el mensaje 3 que llegue después sale como no leído.
   - **Monótono:** en conflicto, `greatest(read_state.last_read_seq, excluded.last_read_seq)`. El
     orden en que llegan los marcados no importa; el cursor nunca baja.
   - `seq < 0` es 400. Repetir el mismo marcado no cambia nada (idempotente).
3. **`GET /servers/{id}/unread` → `{channels: [{channelId, lastSeq, lastReadSeq, unread}]}`**, solo de
   los canales de `AccessPolicy.readableChannels` (404 a extraños y expulsados). Una consulta
   `channels LEFT JOIN read_state` filtrada por esos ids. **No toca `messages`**: cuesta lo mismo con
   10 mensajes que con 10 millones.
4. **El envío no cambia.** No se escribe `read_state` dentro de la transacción que tiene bloqueada la
   fila del canal para reservar el `seq` (ADR 0005). Lo propio cuenta como leído porque el cliente lo
   marca al verlo, igual que lo ajeno.
5. **El cliente marca hasta el final de la racha sin huecos** (`contiguousSeqOf`), no hasta el último
   `seq` que tiene: si le falta el 7 y tiene el 8, el 7 no lo vio. Espera ~1 s (junta los mensajes que
   llegan seguidos) y solo marca con la pestaña a la vista.
6. **Perder el acceso no borra filas.** Dejan de verse porque la lista sale de `readableChannels`, el
   mismo criterio de las notificaciones (ADR 0011). Si el usuario vuelve, recupera su cursor.
7. **`readableChannels` es la regla de `requireChannel` aplicada a todos los canales a la vez**
   (tres consultas, no una por canal), con el mismo predicado `sees` escrito una sola vez. La lista de
   canales, los no leídos y la búsqueda (ADR 0016) filtran con ella; ninguna copia la regla.

## Alternativas descartadas
- **Marcar en el envío** (lo propio como leído): agrega una escritura a la transacción más caliente
  del sistema, la que tiene bloqueada la fila del canal. Y no resuelve lo ajeno: igual hace falta que
  el cliente marque.
- **Marcar al entregar por STOMP:** entregar no es ver. Una pestaña en segundo plano recibe todo y
  el usuario no leyó nada.
- **Contar con `count(*)` sobre `messages`:** correcto, pero recorre mensajes; con la resta no hace
  falta, y es justamente lo que el `seq` sin huecos permite.
- **Un `SELECT` y luego un `UPDATE` desde Java** (leer el cursor, comparar, escribir): dos marcados
  simultáneos leen el mismo valor viejo y gana el último en escribir, que puede ser el menor. Con
  `greatest` dentro de la sentencia, la comparación la hace Postgres con la fila bloqueada.
- **Borrar las filas al expulsar o al sacar de un privado:** suma trabajo a esos caminos y no hace
  falta, porque la lista ya se filtra por acceso.

## Consecuencias
- **Si un día se borran mensajes, la resta sobrecuenta:** contaría como no leídos números que ya no
  son mensajes. Hoy no se puede borrar nada; si se pudiera, habría que llevar la cuenta de borrados
  por canal o volver al `count(*)` (`docs/v2.md`).
- El contador se consulta cada 20 s, como la presencia (ADR 0008): no hay aviso por STOMP. Un mensaje
  nuevo aparece en la lista de canales con a lo sumo ese retraso.
- Con varias pestañas, la que está a la vista marca; la otra no hace retroceder el cursor.
- **Pruebas** (Postgres real): `ReadStateIntegrationTest` (16: resta, sin fila, acotado, monótono, un
  cursor por persona, privados ajenos, expulsados, sacado de un privado),
  `ReadStateConcurrencyIntegrationTest` (4: 20 marcados simultáneos del 1 al 20 en orden aleatorio,
  tres veces con órdenes distintos, y 10 marcados de «todo» mezclados con 10 envíos),
  `AccessPolicyConsistencyTest` (3: para cada actor y canal, `readableChannels` contiene el canal ⇔
  `can(READ)`) y `ReadStateControllerTest` (8). La matriz HTTP
  gana 4 filas (24 celdas): no leídos (1) y marcar leído en público, privado ajeno y privado propio (3).
- **Comprobado que los tests muerden:** ver `docs/DELIVERY.md`, «No leídos y búsqueda (hito 8)».
