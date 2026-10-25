CREATE TABLE watchlist (
    id TEXT NOT NULL PRIMARY KEY,
    scope TEXT NOT NULL DEFAULT 'household',
    kind TEXT NOT NULL,
    item_id TEXT,
    title TEXT NOT NULL,
    channel_id TEXT,
    added_at TEXT NOT NULL
);
CREATE INDEX watchlist_scope ON watchlist (scope, added_at)
