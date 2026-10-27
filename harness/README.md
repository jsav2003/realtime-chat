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
