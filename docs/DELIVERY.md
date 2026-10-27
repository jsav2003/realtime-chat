# Garantía de entrega

**La garantía:** todo mensaje que el servidor confirmó llega a cada miembro del canal
**exactamente una vez y en el mismo orden**, aunque haya desconexiones, reintentos o dos
instancias del backend. Se sostiene con tres piezas (DESIGN.md §3.2):

- un `seq` por canal, asignado por Postgres en la misma transacción que guarda el mensaje;
- `client_msg_id`, para que un reintento devuelva el mensaje ya guardado en vez de crear otro
  (ADR 0005);
- entrega en vivo por STOMP con relay a RabbitMQ y, **siempre**, resincronización por `seq`
  (`GET /channels/{id}/messages?after_seq=N`) para lo que no llegó en vivo.

La entrega en vivo es una optimización; la que garantiza es la resincronización. Por eso el
arnés mide las dos por separado.

## El arnés

[`harness/`](../harness) simula N clientes STOMP contra las **dos instancias** del backend,
detrás del balanceador de `docker compose --profile cluster`:

- todos envían al mismo canal a la vez, cada mensaje con su `client_msg_id`;
- se desconectan al azar y reconectan, a veces a la otra instancia;
- algunos reenvían mensajes ya confirmados con el mismo `client_msg_id`;
- a mitad de la corrida se reinicia una instancia (`docker compose restart`).

Cada cliente hace lo mismo que el frontend: se conecta con su JWT, se suscribe con `receipt`
y, cuando el servidor confirma la suscripción, pide por HTTP lo posterior a lo que tiene.
Al terminar, cada cliente hace una resincronización final y se comprueban cuatro invariantes
contra el historial completo que lee el servidor:

1. **Completo:** cada cliente tiene todos los `seq` de 1 a `last_seq`, sin huecos.
2. **Sin duplicados:** ningún `seq` corresponde a dos mensajes distintos ni ningún mensaje a
   dos `seq`, ni en el servidor ni en un cliente.
3. **Mismo orden:** todos los clientes tienen exactamente la misma secuencia que el servidor.
4. **Fiel:** todo envío confirmado aparece exactamente una vez y con el `seq` que se
   confirmó; ningún envío aparece duplicado; no aparece nada que nadie envió; y reenviar un
   mensaje ya confirmado devuelve 200 con el mismo `seq`.

La **semilla** fija el plan completo: qué manda cada cliente, qué reenvía, a quién se
desconecta, a qué instancia vuelve y cuál instancia se reinicia. Las fallas se disparan por
progreso (cuántos envíos ya se confirmaron) y no por reloj. Lo que la semilla no fija es
cómo el sistema operativo intercala los hilos, por eso las invariantes se comprueban sobre
el resultado y no sobre la traza (y por eso la misma semilla puede dar resultados distintos,
como se ve más abajo).

### Cómo correrlo

```bash
docker compose --profile cluster up -d --build
backend/mvnw -f harness/pom.xml compile exec:java -Dexec.args="--seed 42"
```

Sale con código 0 si las cuatro invariantes dan cero violaciones y con 1 si no. Las pruebas
del propio arnés (que cada invariante detecta lo que debe) corren sin Docker:
`backend/mvnw -f harness/pom.xml test`.

## Resultados

Escenario de todas las corridas: 8 clientes × 80 mensajes (640 envíos), hasta 700 ms entre
envíos de un mismo cliente, 15 % de reenvíos deliberados, 12 desconexiones y el reinicio de
una instancia al 20 % de la corrida. Equipo: Windows 11, 8 hilos, Java 23.0.1, todo en una
sola máquina (Docker Desktop).

| Semilla | Confirmados | Reenvíos | Desconexiones | Reinicio (tardó en volver) | Vistos en vivo | Vistos por resync | Repetidos descartados | Faltaban antes del resync final | Violaciones (1/2/3/4) | Duración |
|---|---|---|---|---|---|---|---|---|---|---|
| 42 | 640 | 85 | 12 | backend-b (48.1 s) | 4898 | 222 | 113 | 0 | 0/0/0/0 | 65.3 s |
| 1 | 640 | 112 | 12 | backend-a (45.7 s) | 4876 | 244 | 137 | 0 | 0/0/0/0 | 62.6 s |
| 2 | 640 | 96 | 12 | backend-a (49.9 s) | 4901 | 219 | 71 | 0 | 0/0/0/0 | 66.0 s |
| 3 | 640 | 94 | 12 | backend-a (57.1 s) | 4850 | 270 | 50 | 0 | 0/0/0/0 | 73.6 s |
| 4 | 640 | 97 | 12 | backend-b (39.3 s) | 4857 | 263 | 119 | 0 | 0/0/0/0 | 55.1 s |
| 5 | 640 | 101 | 12 | backend-a (45.8 s) | 4915 | 205 | 84 | 0 | 0/0/0/0 | 61.2 s |

**Cero violaciones en las seis corridas** (3 840 envíos confirmados, 72 desconexiones, seis
reinicios de instancia).

Cómo leer las columnas:

- *Vistos en vivo / por resync:* cada uno de los 8 clientes ve cada uno de los 640 mensajes
  por primera vez por un solo camino, así que las dos columnas suman siempre 5 120. Entre el
  4,0 % y el 5,3 % de las entregas las tuvo que traer la resincronización: sin ella, el
  resultado sería otro.
- *Repetidos descartados:* entregas de un `seq` que el cliente ya tenía (por ejemplo, un
  mensaje que llega en vivo y también en un resync). Se descartan por `seq` y no cuentan como
  duplicado.
- *Faltaban antes del resync final:* mensajes que a los clientes les faltaban justo antes de
  la resincronización final del arnés, después de dar 3,5 s para que lo que estaba en vuelo
  terminara de llegar. Fue cero en todas: durante la corrida, las resincronizaciones por
  reconexión y por hueco ya habían cerrado todo, no solo la última.
- *Envíos reintentados por falla (no aparece en la tabla):* cero en cinco de las seis corridas y
  uno en la semilla 1 (un envío falló una vez y se confirmó al reintentarlo con el mismo
  `client_msg_id`). Ninguno se rindió sin confirmación. En los reinicios el balanceador
  (`proxy_next_upstream`) usó la otra instancia mientras una estaba caída, así que los envíos
  casi no vieron errores. Los clientes conectados por WebSocket a la
  instancia reiniciada sí perdieron la conexión, reconectaron y se resincronizaron.
- *Tardó en volver:* desde `docker compose restart` hasta que `/actuator/health` responde
  200. La mayor parte es el arranque de Spring Boot dentro de un contenedor.

## Lo que encontró el arnés

Antes de arreglar el cliente, el arnés corrió con tres semillas. Dos pasaron y **una falló**:

| Semilla | Faltaban antes del resync final | Violaciones (1/2/3/4) |
|---|---|---|
| 42 | 0 | 0/0/0/0 |
| 1 | 0 | 0/0/0/0 |
| 2 | 22 | **22/0/287/0** |

El cliente 4 no tenía los `seq` 354 a 375 (22 mensajes seguidos) y la resincronización final
tampoco los trajo. Las invariantes 1 y 3 lo reportaron (la 3 cuenta cada posición desplazada
por los mensajes que faltan); el servidor tenía los 640.

**Causa:** el cliente pedía `after_seq = el mayor seq que tengo`. Al reconectar, un mensaje en
vivo (el 376) llegó antes de que corriera la resincronización; desde ese momento "el mayor
seq" era 376 y pedir "lo posterior al 376" ya no podía devolver el 354 al 375. El hueco
quedaba para siempre. El frontend tenía la misma debilidad (el cursor del resync al
suscribirse y el del botón «Buscar nuevos»): el pedido que detecta el hueco usa el `seq` de
antes del mensaje, pero si ese pedido falla, nada más lo repara.

**Arreglo:** el cursor de resincronización es el **final de la racha completa**, no el mayor
`seq`: el mayor `n` tal que se tienen 1..n (`ClientLog.contiguousSeq` en el arnés,
`contiguousSeqOf` en `frontend/lib/messages.ts`). Pedir desde ahí llena cualquier hueco por
debajo del último mensaje. Con el arreglo, la semilla 2 pasa (tabla de arriba).

Una advertencia honesta: la falla depende de cómo se intercalan los hilos, así que la
semilla 2 no es una reproducción exacta, y que ahora pase no prueba el arreglo por sí sola.
Lo que lo respalda es el análisis de la causa, la prueba unitaria `contiguousSeqStopsAtTheFirstHole`
y las seis corridas limpias.

## Lo que esto no demuestra

- **Una sola máquina.** Las dos instancias, Postgres, RabbitMQ y los 8 clientes comparten el
  mismo equipo. No hay red real entre ellos ni pérdida de paquetes.
- **El reinicio es ordenado.** `docker compose restart` manda SIGTERM y Spring cierra con
  gracia. No se probó `kill -9`, que puede dejar un mensaje confirmado en la base sin avisar
  en vivo (el caso que ADR 0003 deja a cargo de la resincronización). Queda pendiente.
- **RabbitMQ y Postgres no se reinician.** Solo cae una instancia del backend.
- **Los clientes son los del arnés**, que copian la lógica del frontend, no el navegador. La
  prueba con Playwright y dos navegadores es del hito 9.
- **No mide latencia ni rendimiento** (DESIGN.md §8.5): eso también es del hito 9.
- **Una resincronización final siempre ayuda.** Por eso el informe separa lo que llegó en
  vivo, lo que trajo la resincronización y cuántos mensajes faltaban antes de la final.
