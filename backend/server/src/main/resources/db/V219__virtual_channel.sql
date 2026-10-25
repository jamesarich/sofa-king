CREATE TABLE virtual_channel (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    number TEXT,
    settings_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE virtual_slot (
    channel_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    source_json TEXT NOT NULL,
    shuffle INTEGER NOT NULL,
    start_minute INTEGER,
    PRIMARY KEY (channel_id, position)
);
CREATE TABLE virtual_airing (
    channel_id TEXT NOT NULL,
    starts_at TEXT NOT NULL,
    ends_at TEXT NOT NULL,
    item_id TEXT,
    slot INTEGER,
    filler INTEGER NOT NULL,
    PRIMARY KEY (channel_id, starts_at)
)
