-- Handle único para las menciones (@beto). El nombre visible (display_name) no es único, así que
-- no sirve para saber a quién se menciona (DESIGN.md §5, ADR 0011).
ALTER TABLE users ADD COLUMN username TEXT;

-- Relleno para los usuarios que ya existían: la parte local del correo, en minúsculas y solo con
-- letras, números y guion bajo (máximo 20). Si queda corta, o ya la tomó otro usuario, se le agrega
-- el id. En una base nueva no hay filas y esto no hace nada.
WITH base AS (
    SELECT id,
           left(regexp_replace(lower(split_part(email, '@', 1)), '[^a-z0-9_]', '_', 'g'), 20) AS handle
    FROM users
), ranked AS (
    SELECT id, handle, row_number() OVER (PARTITION BY handle ORDER BY id) AS n
    FROM base
)
UPDATE users u
SET username = CASE
        WHEN char_length(r.handle) >= 3 AND r.n = 1 THEN r.handle
        ELSE left(r.handle, 20 - char_length(u.id::text) - 1) || '_' || u.id
    END
FROM ranked r
WHERE r.id = u.id;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_username_format CHECK (username ~ '^[A-Za-z0-9_]{3,20}$');

-- Sin distinguir mayúsculas: @Beto y @beto son la misma persona.
CREATE UNIQUE INDEX users_username_lower_key ON users (lower(username));
