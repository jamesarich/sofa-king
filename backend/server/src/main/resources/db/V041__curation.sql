CREATE TABLE curation_rule(
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    position INTEGER NOT NULL,
    rule_json TEXT NOT NULL
);
CREATE TABLE curation_entry_state(
    entry_id TEXT PRIMARY KEY,
    unavailable_since TEXT NOT NULL
);
CREATE TABLE curation_adopted(
    channel_id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL,
    adopted_at TEXT NOT NULL
);
CREATE TABLE curation_change(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    applied_at TEXT NOT NULL,
    entry_id TEXT,
    action_json TEXT NOT NULL
);
CREATE INDEX curation_change_applied_at ON curation_change(applied_at)
