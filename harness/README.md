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

## El límite de mensajes

El backend limita los mensajes nuevos por usuario (20 y 30 por minuto por defecto, ADR 0009) y el
arnés manda unos 80 por cliente. No hace falta tocar nada: el arnés espera el `Retry-After` de
cada `429` y reintenta con el mismo `client_msg_id`, y cuenta aparte cuántos hubo. Eso alarga la
corrida. Para una corrida rápida, sube el límite al levantar el cluster:

```bash
RATE_LIMIT_CAPACITY=100000 RATE_LIMIT_REFILL_PER_MINUTE=100000 \
  docker compose --profile cluster up -d --build
```
