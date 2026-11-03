-- Adjuntos (hito 7, ADR 0014). Una fila nace "pendiente" cuando el cliente pide subir un archivo:
-- sin uploaded_at (todavía no se confirmó la subida) y sin message_id (todavía no se envió el mensaje).
-- Cada adjunto pertenece a un solo mensaje: message_id se llena una vez, con un UPDATE condicional.
CREATE TABLE attachments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id  BIGINT      NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    uploader_id BIGINT      NOT NULL REFERENCES users (id),
    message_id  BIGINT      REFERENCES messages (id) ON DELETE CASCADE,
    -- La clave del objeto en S3. Nunca lleva el nombre que puso el usuario.
    s3_key      TEXT        NOT NULL UNIQUE,
    filename    TEXT        NOT NULL CHECK (char_length(filename) BETWEEN 1 AND 255),
    -- El tope real (app.storage.max-size) vive en la configuración; aquí solo lo que nunca tiene sentido.
    size        BIGINT      NOT NULL CHECK (size > 0),
    content_type TEXT       NOT NULL CHECK (char_length(content_type) BETWEEN 3 AND 100),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Lo llena POST /attachments/{id}/complete tras comprobar el objeto en S3.
    uploaded_at TIMESTAMPTZ
);

-- Adjuntos de una página de mensajes: se leen con message_id = ANY(...).
CREATE INDEX attachments_message_idx ON attachments (message_id) WHERE message_id IS NOT NULL;

-- El limpiador de huérfanos recorre solo los que nunca llegaron a un mensaje, del más viejo al más nuevo.
CREATE INDEX attachments_orphans_idx ON attachments (created_at) WHERE message_id IS NULL;
