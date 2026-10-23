CREATE TABLE library_intro (
    item_id TEXT PRIMARY KEY,
    file_key TEXT NOT NULL,
    fingerprint BLOB,
    intro_start_ms INTEGER,
    intro_end_ms INTEGER,
    read_at TEXT NOT NULL
)
