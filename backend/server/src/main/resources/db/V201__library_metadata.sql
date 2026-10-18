CREATE TABLE library_metadata (
    item_id TEXT PRIMARY KEY,
    tmdb_id INTEGER,
    checked_at TEXT NOT NULL,
    facts_json TEXT
)
