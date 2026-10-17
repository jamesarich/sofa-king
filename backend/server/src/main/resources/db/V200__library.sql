CREATE TABLE library (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    path TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    scanned_at TEXT,
    scan_ok INTEGER,
    scan_note TEXT
);
CREATE TABLE library_item (
    id TEXT PRIMARY KEY,
    library_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    parent_id TEXT,
    path TEXT,
    title TEXT NOT NULL,
    sort_title TEXT NOT NULL,
    year INTEGER,
    season INTEGER,
    episode INTEGER,
    episode_end INTEGER,
    aired TEXT,
    overview TEXT,
    duration_ms INTEGER,
    size INTEGER,
    modified_at TEXT,
    media_json TEXT,
    details_json TEXT,
    poster TEXT,
    backdrop TEXT,
    added_at TEXT NOT NULL,
    missing_since TEXT
);
CREATE INDEX library_item_library ON library_item(library_id, kind);
CREATE INDEX library_item_parent ON library_item(parent_id);
CREATE INDEX library_item_added ON library_item(added_at);
CREATE TABLE play_state (
    item_id TEXT PRIMARY KEY,
    position_ms INTEGER NOT NULL,
    duration_ms INTEGER,
    watched INTEGER NOT NULL,
    updated_at TEXT NOT NULL
)
