CREATE TABLE client_crash (
    id TEXT PRIMARY KEY,
    client_id TEXT,
    client_name TEXT NOT NULL,
    received_at TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    exception TEXT NOT NULL,
    message TEXT NOT NULL,
    frames TEXT NOT NULL,
    app_version TEXT NOT NULL,
    platform TEXT NOT NULL,
    os_version TEXT NOT NULL,
    device TEXT NOT NULL
);
CREATE INDEX client_crash_received ON client_crash(received_at)
