-- Un mensaje puede ser solo adjuntos (hito 7, ADR 0014): el texto puede quedar vacío.
-- Que haya texto o al menos un adjunto lo exige MessageService, porque el adjunto vive en otra tabla
-- y un CHECK no puede mirar otra tabla. El tope de 4000 caracteres se conserva.
ALTER TABLE messages DROP CONSTRAINT messages_body_check;
ALTER TABLE messages ADD CONSTRAINT messages_body_check CHECK (char_length(body) <= 4000);
