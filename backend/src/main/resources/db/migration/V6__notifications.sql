-- Notificaciones de menciones (ADR 0011, ADR 0013). Las escribe el worker, una por (mensaje, persona).
CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message_id BIGINT      NOT NULL REFERENCES messages (id) ON DELETE CASCADE,
    -- Redundante con el mensaje, pero permite saber qué canales tienen notificaciones de alguien
    -- sin recorrer sus mensajes: la lista se filtra por los canales que todavía puede ver.
    channel_id BIGINT      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    -- Esta restricción ES la deduplicación del worker. Los eventos llegan al menos una vez, así que
    -- uno puede llegar repetido; la base impide que la misma persona tenga dos notificaciones por
    -- el mismo mensaje, y lo hace sin carrera aunque dos workers procesen el mismo evento a la vez.
    UNIQUE (message_id, user_id)
);

-- Las notificaciones de una persona, las más nuevas primero.
CREATE INDEX notifications_user_idx ON notifications (user_id, id DESC);
