CREATE TABLE channels (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    server_id  BIGINT      REFERENCES servers (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL,
    kind       TEXT        NOT NULL CHECK (kind IN ('text', 'private', 'dm')),
    last_seq   BIGINT      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((kind = 'dm') = (server_id IS NULL)),
    UNIQUE (server_id, name)
);
CREATE INDEX channels_server_idx ON channels (server_id);

CREATE TABLE channel_members (
    channel_id BIGINT NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX channel_members_user_idx ON channel_members (user_id);

CREATE TABLE invites (
    code       TEXT        PRIMARY KEY,
    server_id  BIGINT      NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    created_by BIGINT      NOT NULL REFERENCES users (id),
    expires_at TIMESTAMPTZ,
    max_uses   INT,
    uses       INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (max_uses IS NULL OR uses <= max_uses)
);
CREATE INDEX invites_server_idx ON invites (server_id);
