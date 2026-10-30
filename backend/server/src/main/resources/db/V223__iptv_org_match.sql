CREATE TABLE iptv_org_match (
    station_key TEXT NOT NULL PRIMARY KEY,
    channel_id TEXT,
    decision TEXT NOT NULL,
    decided_at TEXT NOT NULL
)
