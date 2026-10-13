CREATE TABLE provider_state(
    provider_id TEXT NOT NULL,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    expires_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(provider_id, key)
)
