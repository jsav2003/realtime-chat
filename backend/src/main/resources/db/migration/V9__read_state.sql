-- Hasta dónde leyó cada persona en cada canal (hito 8, DESIGN.md §3.3, ADR 0015).
-- No leídos = channels.last_seq - last_read_seq: una resta, sin contar mensajes. Es exacta porque
-- el seq de un canal no tiene huecos (ADR 0001 y 0005).
-- Sin fila = nunca marcó nada = last_read_seq 0 (todo el canal sin leer); por eso no hay que crear
-- filas al unirse a un servidor ni al crear un canal.
CREATE TABLE read_state (
    user_id       BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id    BIGINT NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    last_read_seq BIGINT NOT NULL CHECK (last_read_seq >= 0),
    PRIMARY KEY (user_id, channel_id)
);
