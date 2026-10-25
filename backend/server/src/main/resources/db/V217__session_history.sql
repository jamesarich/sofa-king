CREATE TABLE session_history (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    client_id TEXT,
    address TEXT,
    channel_id TEXT,
    item_id TEXT,
    recording_id TEXT,
    provider_id TEXT,
    mode TEXT NOT NULL,
    reason TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT NOT NULL,
    bytes INTEGER
);
CREATE INDEX session_history_ended ON session_history(ended_at)
