CREATE TABLE api_token (
    id TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    secret_hash TEXT NOT NULL UNIQUE,
    scope TEXT NOT NULL,
    client_id TEXT,
    created_at TEXT NOT NULL,
    last_used_at TEXT,
    revoked_at TEXT
);
CREATE INDEX api_token_client ON api_token(client_id)
