# ADR 0008 · Presencia: una entrada por conexión con vencimiento, y se consulta

**Estado:** aceptada · 2026-10-29 · Complementa DESIGN.md §5 y la ADR 0007

## Contexto
Un usuario está en línea si tiene al menos una conexión viva en **cualquier** instancia
(DESIGN.md §5). Tres cosas lo complican:

- Tiene varias pestañas: cerrar una no lo apaga.
- Hay dos instancias: ninguna conoce los sockets de la otra (`SessionRegistry` es local).
- Una pestaña o una instancia pueden morir sin avisar, y nadie ejecuta el cierre limpio.
  Un contador que solo sube y baja se queda en línea para siempre.

## Decisión
1. **Un ZSET por usuario en Redis**, `presence:{userId}`, con un miembro por conexión
   (`{instanceId}:{sessionId}`) y como puntaje la hora a la que esa conexión deja de contar.
   «En línea» es «le queda algún miembro sin vencer».
2. **La hora sale de Redis** (`TIME` dentro del script Lua), no de la instancia que escribe:
   dos instancias con relojes distintos se pisarían los vencimientos.
3. **Los tres pasos son scripts o comandos atómicos.** `touch.lua` escribe el miembro y
   renueva el vencimiento de la clave; `online.lua` borra lo vencido y cuenta lo que queda,
   para muchos usuarios en una sola llamada; el cierre limpio es un `ZREM`.
4. **Quién escribe:**
   - `PresenceTracker` (un `ConnectionListener` que avisa `SessionRegistry`) marca la conexión
     al autenticarse el `CONNECT` y la borra cuando el socket se cierra, por la razón que sea,
     incluida una expulsión (ADR 0007).
   - `PresenceRefresher` renueva cada 15 s las conexiones que **esa instancia** tiene abiertas,
     con un vencimiento de 45 s (tres latidos). Si la instancia muere, nadie renueva y las
     suyas vencen en menos de 45 s.
5. **Se consulta, no se avisa.** `GET /servers/{id}/presence` devuelve los ids de los miembros
   de ese servidor que están en línea (`AccessPolicy` VIEW: 404 a quien no es miembro). La
   interfaz lo pide cada 20 s y al volver a la pestaña. No hay tópico STOMP de presencia.
6. **Redis no es la fuente de verdad** (DESIGN.md §6): si no responde, las conexiones y los
   cierres siguen funcionando, solo se registra una advertencia, y la consulta responde 503
   para que la interfaz no pinte puntos (un punto gris afirmaría que todos están fuera).

## Alternativas descartadas
- **Una clave con TTL por conexión (`SET presence:{user}:{conn} EX 45`):** es lo que dice
  literalmente la §5, pero saber quién está en línea exige un `SCAN` por patrón; con el ZSET
  la consulta es una llamada.
- **Un contador por usuario (`INCR` al conectar, `DECR` al cerrar):** se desajusta con la
  primera caída sin cierre limpio y no vence.
- **Avisar los cambios por un tópico STOMP:** más inmediato, pero suma un destino y filas en
  la matriz STOMP, y no evita la consulta: si una instancia muere, el aviso de salida no sale
  nunca y hay que descubrirlo por vencimiento de todos modos. Se puede añadir encima después.
- **Presencia en Postgres:** convierte cada latido en una escritura de la fuente de verdad
  para un dato que puede perderse sin consecuencias.

## Consecuencias
- Un cierre limpio apaga al usuario en el acto; una caída lo apaga a los 45 s como máximo;
  un cambio se ve en la interfaz con hasta 20 s más de retraso.
- **El WebSocket solo se abre en la página de un canal.** Quien mira la lista de canales de un
  servidor no tiene socket y aparece sin conexión para los demás. La interfaz muestra siempre
  en línea al propio usuario, porque está mirando la pantalla. Si más adelante importa, se
  abre un socket por sesión (no por canal) y se suscribe a lo que se vea.
- El latido y el cierre pueden cruzarse: si el socket se cierra mientras se renueva, el
  `touch` podría resucitar la entrada. `PresenceRefresher` comprueba con `isBound` después de
  cada `touch` y la borra si el socket ya no está.
- `online.lua` usa varias claves en una llamada: sirve con un Redis único, que es el caso;
  un Redis Cluster exigiría una llamada por clave o etiquetas de ranura.
- Lo prueban `PresenceStoreIntegrationTest` (varias conexiones, vencimiento y renovación),
  `PresenceTrackingIntegrationTest` (sockets reales, dos pestañas, otra instancia, expulsión,
  latido) y `PresenceServiceIntegrationTest` (solo miembros del servidor).
- Comprobado que el test muerde (29/10): si `SessionRegistry` deja de avisar el cierre a los
  oyentes, fallan 5 de los 6 tests de `PresenceTrackingIntegrationTest` (el usuario no se apaga
  al cerrar la última pestaña ni al expulsarlo) y 3 de `SessionRegistryTest`.
- Probado a mano en Chrome con el cluster: con dos sockets abiertos Beto aparece en línea en
  la interfaz de Ana; al cerrar uno sigue en línea; al cerrar los dos desaparece.
