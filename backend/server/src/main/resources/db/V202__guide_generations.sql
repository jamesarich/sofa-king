ALTER TABLE guide_import ADD COLUMN generation INTEGER NOT NULL DEFAULT 0;
CREATE TABLE guide_channel_next(
    guide_id TEXT NOT NULL,
    generation INTEGER NOT NULL DEFAULT 0,
    channel_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    icon TEXT,
    PRIMARY KEY(guide_id, generation, channel_id)
);
INSERT INTO guide_channel_next(guide_id, channel_id, display_name, icon) SELECT guide_id, channel_id, display_name, icon FROM guide_channel;
DROP TABLE guide_channel;
ALTER TABLE guide_channel_next RENAME TO guide_channel;
CREATE TABLE guide_programme_next(
    guide_id TEXT NOT NULL,
    generation INTEGER NOT NULL DEFAULT 0,
    channel_id TEXT NOT NULL,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    details_json TEXT,
    PRIMARY KEY(guide_id, generation, channel_id, starts_at, ends_at, title)
);
INSERT INTO guide_programme_next(guide_id, channel_id, starts_at, ends_at, title, description, details_json) SELECT guide_id, channel_id, starts_at, ends_at, title, description, details_json FROM guide_programme;
DROP TABLE guide_programme;
ALTER TABLE guide_programme_next RENAME TO guide_programme;
CREATE INDEX guide_programme_window ON guide_programme(guide_id, generation, channel_id, ends_at)
