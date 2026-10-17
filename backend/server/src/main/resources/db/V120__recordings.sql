CREATE TABLE recording_rule (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    channel_id TEXT,
    new_only INTEGER NOT NULL,
    keep INTEGER NOT NULL,
    start_padding_seconds INTEGER NOT NULL,
    end_padding_seconds INTEGER NOT NULL,
    priority INTEGER NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE recording (
    id TEXT PRIMARY KEY,
    rule_id TEXT,
    channel_id TEXT NOT NULL,
    channel_name TEXT NOT NULL,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL,
    start_padding_seconds INTEGER NOT NULL,
    end_padding_seconds INTEGER NOT NULL,
    title TEXT NOT NULL,
    episode_title TEXT,
    description TEXT,
    season INTEGER,
    episode INTEGER,
    movie INTEGER NOT NULL,
    year INTEGER,
    image TEXT,
    state TEXT NOT NULL,
    reason TEXT,
    path TEXT,
    bytes INTEGER NOT NULL,
    protected INTEGER NOT NULL,
    position_ms INTEGER NOT NULL,
    watched INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX recording_airing ON recording(channel_id, starts_at);
CREATE INDEX recording_state ON recording(state, starts_at)
