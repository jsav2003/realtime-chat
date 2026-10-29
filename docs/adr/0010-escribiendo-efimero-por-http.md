# ADR 0010 · «Escribiendo…» es un aviso efímero que entra por HTTP

**Estado:** aceptada · 2026-10-29 · Complementa la ADR 0006 y DESIGN.md §5

## Contexto
Mostrar «Ana está escribiendo…» es el caso típico para mandar un `SEND` por el WebSocket: es
chico, frecuente y no importa si se pierde. Pero la ADR 0006 cerró `SEND` por completo: con el
relay a RabbitMQ un `SEND` a un tópico llega a todos sin pasar por nuestra lógica, y abrir una
excepción «solo para escribiendo» es abrir un destino que cualquiera puede usar para falsificar
quién escribe.

## Decisión
1. **Entra por HTTP:** `POST /channels/{id}/typing` responde 204. Pasa por `AccessPolicy` con
   `WRITE`: escribe quien puede escribir, y un extraño recibe 404, igual que al enviar un
   mensaje. El servidor pone el autor (el del token), no el cliente.
2. **Sale por su propio tópico:** `/topic/channel.{id}.typing`, con `{userId, displayName}`.
   En RabbitMQ es otra clave de ruteo que `channel.{id}`: quien escucha los mensajes no recibe
   los avisos y viceversa. `ChannelTopic` reconoce los dos destinos y nada más (sin comodines);
   `SUBSCRIBE` a cualquiera de los dos pasa por `AccessPolicy` con `READ`.
3. **Es efímero:** no se guarda, no lleva `seq`, no se resincroniza y queda **fuera de las
   garantías de entrega** (DESIGN.md §3). Si un aviso se pierde, el siguiente tecleo manda otro.
4. **Se frena en el servidor:** `SET typing:{canal}:{usuario} NX PX 2000` en Redis. Dentro de
   esa ventana los avisos siguientes responden 204 sin publicar. Así un cliente que manda un
   aviso por tecla no inunda el canal; el frontend además avisa como mucho cada 2 s.
5. **Si Redis cae, el aviso sale sin freno:** es un adorno y no justifica fallar.
6. El frontend lo muestra 4 s desde el último aviso, ignora el propio y lo borra cuando llega
   un mensaje de esa persona.

## Alternativas descartadas
- **`SEND` por STOMP a `/app/...` con un `@MessageMapping`:** reabre la superficie que la
  ADR 0006 cerró y agrega filas «permitidas» a la matriz STOMP.
- **Reusar el tópico de mensajes con otro tipo de evento:** los clientes tendrían que
  distinguir en el cuerpo, y un suscriptor de mensajes recibiría avisos que no puede resincronizar.
- **Guardar quién escribe en Redis y consultarlo:** más estado para un dato de dos segundos.
- **Sin freno en el servidor:** confía en que el cliente se porte bien.

## Consecuencias
- Una sola ruta de escritura sigue siendo cierta: lo que llega a los suscriptores pasa por
  nuestra lógica (mensajes) o por `AccessPolicy` (avisos).
- Las matrices ganan filas: HTTP `POST /channels/{id}/typing` (público, privado sin y con
  member) y STOMP `SUBSCRIBE`/`SEND` sobre el tópico `.typing`. El test que exige que todo
  destino esté en la matriz sigue protegiendo contra olvidos.
- Cada aviso que pasa el freno cuesta una lectura de `users` para el nombre: como mucho una
  cada 2 s por usuario y canal.
- Lo prueba `TypingIntegrationTest` con sockets y Redis reales: llega a quien escucha
  `.typing` y no a quien escucha los mensajes (ni al revés), tres avisos seguidos publican uno,
  pasada la ventana vuelve a salir, un extraño recibe 404, y llega a un cliente de la otra
  instancia.
- Comprobado que el test muerde (29/10): si el destino `.typing` se salta `AccessPolicy` en
  `SUBSCRIBE`, fallan 7 celdas de `StompAuthorizationMatrixTest`.
- Probado a mano en Chrome: un aviso de Beto muestra «Beto está escribiendo…» en la pantalla de
  Ana y se borra solo a los 4 s; un segundo aviso inmediato responde 204 y se frena.
