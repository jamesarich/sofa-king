CREATE TABLE lineup_entry(
    id TEXT PRIMARY KEY,
    position INTEGER NOT NULL,
    number TEXT NOT NULL,
    name TEXT NOT NULL,
    group_name TEXT,
    logo TEXT,
    enabled INTEGER NOT NULL,
    guide_id TEXT,
    guide_channel TEXT,
    updated_at TEXT NOT NULL
);
CREATE TABLE lineup_offering(
    entry_id TEXT NOT NULL REFERENCES lineup_entry(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    offering_id TEXT NOT NULL,
    PRIMARY KEY(entry_id, position)
);
CREATE TABLE guide_import(
    guide_id TEXT PRIMARY KEY,
    imported_at TEXT NOT NULL
);
CREATE TABLE guide_channel(
    guide_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    icon TEXT,
    PRIMARY KEY(guide_id, channel_id)
);
CREATE TABLE guide_programme(
    guide_id TEXT NOT NULL,
    channel_id TEXT NOT NULL,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    PRIMARY KEY(guide_id, channel_id, starts_at, ends_at, title)
);
CREATE INDEX guide_programme_window ON guide_programme(guide_id, channel_id, ends_at)
