CREATE TABLE logo_cache(
    key TEXT PRIMARY KEY,
    source_url TEXT NOT NULL,
    content_type TEXT,
    size INTEGER NOT NULL,
    content_hash TEXT,
    fetched_at TEXT,
    failed_at TEXT,
    failures INTEGER NOT NULL
)
