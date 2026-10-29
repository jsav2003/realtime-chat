# ADR 0009 · Límite de mensajes: token bucket atómico en Redis, cobrado al mensaje nuevo

**Estado:** aceptada · 2026-10-29 · Complementa DESIGN.md §5 y §8.3 y las ADR 0005 y 0006

## Contexto
Hay que limitar cuántos mensajes puede mandar un usuario (DESIGN.md §5). Tres problemas:

- **Carrera en el contador.** Leer el contador, decidir y escribirlo en dos pasos deja pasar de
  más cuando llegan solicitudes a la vez. Y con dos instancias el contador no puede vivir en
  memoria.
- **Dónde cobrar.** El envío ya tiene reglas finas: un extraño recibe 404, un reintento de un
  mensaje guardado recibe 200 (ADR 0005) y un envío fallido no debe dejar huecos en el `seq`.
- **Qué hacer si Redis cae.** Redis puede perderse sin perder mensajes (DESIGN.md §6).

## Decisión
1. **Token bucket en un script Lua** (`ratelimit/token_bucket.lua`). Redis ejecuta el script
   entero sin intercalar otros comandos: recarga según el tiempo transcurrido, decide, descuenta
   y guarda, todo en una vez. El tiempo sale de `TIME` de Redis, no de la instancia. La clave
   `rl:msg:{userId}` vence sola cuando el bucket ya estaría lleno.
2. **Parámetros:** capacidad 20 y recarga de 30 por minuto, por usuario
   (`app.ratelimit.capacity`, `app.ratelimit.refill-per-minute`; en el cluster, las variables
   `RATE_LIMIT_CAPACITY` y `RATE_LIMIT_REFILL_PER_MINUTE`). Una recarga de 0 no arranca: quien
   agotara el bucket no podría volver a escribir.
3. **Se cobra en `MessageService.sendInTransaction`**, en este orden:
   `AccessPolicy` → búsqueda del reintento → **límite** → reserva del `seq` → `INSERT`.
   - Después de `AccessPolicy`: un extraño recibe 404, nunca 429 (no se le revela nada).
   - Después del reintento: reenviar un mensaje ya guardado recibe 200 y **no gasta cupo**.
   - Antes del `seq`: un 429 no reserva ninguno, así que no deja huecos.
4. **Respuesta:** `429` con `Retry-After` en segundos (mínimo 1) y el cuerpo de error
   habitual. El cliente espera y reintenta **con el mismo `client_msg_id`** (frontend y arnés).
5. **Si Redis no responde, el mensaje pasa** y se registra una advertencia
   (`MessageRateLimiter`). Un freno que se cae no debe impedir escribir.

## Alternativas descartadas
- **`GET` del contador y `SET` después, o `INCR` + `EXPIRE` por separado:** carrera.
  `INCR` solo es atómico, pero una ventana fija deja pasar el doble en el borde de dos
  ventanas y no recarga de forma gradual.
- **`WATCH`/`MULTI` con reintento:** funciona, pero cada colisión es otro viaje a Redis; el
  script lo resuelve en uno.
- **Limitar en un filtro HTTP antes de `AccessPolicy`:** gastaría cupo en solicitudes que
  igual se rechazan, y un 429 antes del 404 le diría a un extraño que el canal existe.
- **Fallar cerrado (503) si Redis cae:** convierte una caída de un componente auxiliar en una
  caída del chat.
- **Limitar también `GET`:** no es el abuso que importa aquí; se puede añadir con otra clave.

## Consecuencias
- Lo prueba `TokenBucketConcurrencyIntegrationTest`: 100 solicitudes simultáneas contra un
  bucket de capacidad 20 y sin recarga dejan pasar exactamente 20 (DESIGN.md §8.3).
  `MessageRateLimitIntegrationTest` prueba el orden: el mensaje 21 da 429 y `last_seq` queda en
  20, el reintento con el bucket vacío da 200, y un canal ajeno sigue dando 404.
- **El reintento que pierde la carrera gasta un token.** Si dos reintentos del mismo mensaje
  llegan a la vez (ADR 0005), los dos pasan la búsqueda antes de que uno confirme y los dos
  cobran; el que pierde se revierte pero el token ya se gastó en Redis, que no participa de la
  transacción. Es un costo de un token en un caso raro; la alternativa sería cobrar después
  del `INSERT`, y entonces un envío rechazado por el límite ya habría reservado el `seq`.
- Un envío que falla por otra razón después de cobrar también gasta su token.
- Con Redis caído no hay límite: la protección contra abuso se pierde mientras dure.
- El arnés de entrega manda unos 80 mensajes por cliente y con el límite por defecto choca: trata
  el 429 como espera y no como falla (no gasta sus 80 intentos) y cuenta cuántos hubo. Para
  corridas rápidas se sube el límite en el `.env`.
- Comprobado que el test muerde (29/10): con un bucket ingenuo (`GET` y `SET` en dos pasos)
  las 100 solicitudes simultáneas pasan **todas** (100, no 20).
- Con el arnés de entrega y el límite por defecto, 434 y 446 envíos fueron frenados con 429,
  esperaron y se confirmaron una sola vez; cero violaciones (`docs/DELIVERY.md`).
- Probado a mano en Chrome: con el cupo vacío, tres mensajes seguidos desde la interfaz
  mostraron «Vas muy rápido…» y quedaron como #40, #41 y #42, una vez cada uno.
