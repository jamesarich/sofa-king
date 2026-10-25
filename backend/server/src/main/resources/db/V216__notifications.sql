CREATE TABLE notification_target (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    url TEXT NOT NULL,
    secret TEXT,
    events_json TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE notification_delivery (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    target_id TEXT NOT NULL,
    event TEXT NOT NULL,
    at TEXT NOT NULL,
    ok INTEGER NOT NULL,
    status INTEGER,
    error TEXT,
    attempts INTEGER NOT NULL
);
CREATE INDEX notification_delivery_target ON notification_delivery(target_id, id)
