-- Outbox (ADR 0004, ADR 0012): el evento se escribe en la misma transacción que el dato que lo
-- origina, y un publicador lo lleva a RabbitMQ después. Si el proceso muere entre guardar y
-- publicar, el evento sigue aquí esperando; no se pierde.
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

-- El publicador solo mira lo pendiente. Con el índice parcial, la consulta no recorre las filas ya
-- publicadas aunque la tabla crezca.
CREATE INDEX outbox_pending_idx ON outbox (id) WHERE published_at IS NULL;
