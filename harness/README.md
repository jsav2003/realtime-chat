# Arnés de entrega

Simula N clientes contra las **dos instancias** del backend y comprueba, al terminar, los
cuatro invariantes de [`DESIGN.md` §8.1](../DESIGN.md): completo, sin duplicados, mismo
orden y fiel. Los resultados están en [`docs/DELIVERY.md`](../docs/DELIVERY.md).

## Cómo correrlo

```bash
# 1. El cluster: Postgres, Redis, RabbitMQ, backend-a, backend-b y el balanceador.
docker compose --profile cluster up -d --build

# 2. El arnés, con semilla fija.
backend/mvnw -f harness/pom.xml compile exec:java -Dexec.args="--seed 42"
```

Las pruebas del propio arnés (que cada invariante detecta lo que debe detectar) no necesitan
Docker: `backend/mvnw -f harness/pom.xml test`.

## Arnés de menciones

El hermano real del test `WorkerInterruptedIntegrationTest` ([`DESIGN.md` §8.3](../DESIGN.md)):
N clientes envían mensajes que **mencionan** a otros (`@usuario`) y, mientras tanto, se le manda
**SIGKILL** al contenedor `worker` varias veces (`docker compose --profile cluster kill worker`),
cada una a un progreso de la corrida fijado por la semilla y con el worker caído entre 0,3 y 3 s.
Al terminar compara lo que cada persona recibe por `GET /notifications` con los mensajes que
quedaron guardados, y comprueba tres invariantes:

1. **Completa:** toda mención de un mensaje guardado terminó notificada.
2. **Sin duplicados:** nadie tiene dos notificaciones por el mismo mensaje.
3. **Fiel:** nadie recibió una notificación de un mensaje que no lo mencionaba (ni de uno propio).

Además mide cuánto tarda el worker en terminar todo después del último envío. Los reenvíos
deliberados de mensajes ya confirmados no deben notificar otra vez: ya cuentan en «Fiel».

```bash
# El cluster incluye el servicio `worker` (los backends tienen el worker apagado).
docker compose --profile cluster up -d --build

backend/mvnw -f harness/pom.xml compile exec:java \
  -Dharness.main=com.jsav.chat.harness.MentionsHarness -Dexec.args="--seed 42"
```

Opciones propias: `--kills N` (veces que se mata al worker; 6 por defecto) y `--mention-rate X`
(fracción de mensajes que mencionan a alguien; 0,7). Usa las mismas del arnés de entrega para lo
demás, pero con **40 mensajes por cliente** si no se pide otra cosa: con 80 y 8 clientes alguien
recibiría más menciones de las que el servidor entrega en una consulta (100).

Si no consigue matar al worker ni una vez (por ejemplo, Docker no responde), lo dice como una
violación más: un pase sin caídas no prueba nada. Las pruebas del propio arnés (el plan sembrado,
las invariantes y el que mata al worker) no necesitan Docker: `backend/mvnw -f harness/pom.xml test`.

## El límite de mensajes

El backend limita los mensajes nuevos por usuario (20 y 30 por minuto por defecto, ADR 0009) y el
arnés manda unos 80 por cliente. No hace falta tocar nada: el arnés espera el `Retry-After` de
cada `429` y reintenta con el mismo `client_msg_id`, y cuenta aparte cuántos hubo. Eso alarga la
corrida. Para una corrida rápida, sube el límite al levantar el cluster:

```bash
RATE_LIMIT_CAPACITY=100000 RATE_LIMIT_REFILL_PER_MINUTE=100000 \
  docker compose --profile cluster up -d --build
```
