CREATE TABLE ad_break (
    id TEXT PRIMARY KEY,
    points BLOB NOT NULL,
    provider_id TEXT NOT NULL,
    channels TEXT NOT NULL,
    days TEXT NOT NULL,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_hit_at TEXT,
    hits INTEGER NOT NULL,
    false_hits INTEGER NOT NULL,
    blocked INTEGER NOT NULL
)
