-- Búsqueda de texto completo en los mensajes (hito 8, ADR 0016).
-- La columna la calcula Postgres en cada INSERT: el envío no cambia ni puede olvidarse de llenarla.
-- 'spanish' deriva las palabras («mensajes» encuentra «mensaje») y descarta las vacías («de», «la»).
-- No quita tildes (sin unaccent): «canción» no encuentra «cancion» (docs/v2.md).
-- Ojo en producción: ADD COLUMN ... STORED reescribe la tabla entera con un lock exclusivo. Con
-- muchos mensajes se haría con una columna normal, un trigger, un relleno por lotes y
-- CREATE INDEX CONCURRENTLY (ADR 0016).
ALTER TABLE messages ADD COLUMN search tsvector
    GENERATED ALWAYS AS (to_tsvector('spanish', body)) STORED;

CREATE INDEX messages_search_idx ON messages USING gin (search);
