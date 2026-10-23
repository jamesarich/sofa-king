CREATE TABLE ad_channel (
    channel_id TEXT PRIMARY KEY,
    provider_id TEXT NOT NULL,
    watched_ms INTEGER NOT NULL,
    last_watched_at TEXT,
    breaks INTEGER NOT NULL,
    learned INTEGER NOT NULL,
    marked_at TEXT,
    listened_ms INTEGER NOT NULL,
    last_listened_at TEXT
)
