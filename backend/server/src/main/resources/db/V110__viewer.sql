CREATE TABLE viewer (
    client_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    preferences_json TEXT,
    updated_at TEXT
)
