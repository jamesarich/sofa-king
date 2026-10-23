CREATE TABLE library_scrub (
    item_id TEXT PRIMARY KEY,
    file_key TEXT NOT NULL,
    interval_ms INTEGER NOT NULL,
    tile_width INTEGER NOT NULL,
    tile_height INTEGER NOT NULL,
    columns INTEGER NOT NULL,
    rows INTEGER NOT NULL,
    sheets INTEGER NOT NULL,
    frames INTEGER NOT NULL,
    made_at TEXT NOT NULL
)
