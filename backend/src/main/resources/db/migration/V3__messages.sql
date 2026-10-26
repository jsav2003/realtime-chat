-- Mensajes (DESIGN.md §3.2, ADR 0001). El seq lo asigna el backend con
-- UPDATE channels SET last_seq = last_seq + 1 ... RETURNING, en la misma transacción
-- que el INSERT de aquí abajo.
CREATE TABLE messages (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id    BIGINT      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    seq           BIGINT      NOT NULL CHECK (seq > 0),
    author_id     BIGINT      NOT NULL REFERENCES users (id),
    client_msg_id UUID        NOT NULL,
    body          TEXT        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Dos mensajes nunca comparten seq en un canal. El índice de esta restricción
    -- también sirve la paginación por cursor (after_seq / before_seq).
    UNIQUE (channel_id, seq),
    -- Un reintento con la misma clave no crea otro mensaje.
    UNIQUE (author_id, client_msg_id)
);
