CREATE TABLE library_match (
    item_id TEXT NOT NULL PRIMARY KEY,
    tmdb_id INTEGER NOT NULL,
    chosen_at TEXT NOT NULL
);
