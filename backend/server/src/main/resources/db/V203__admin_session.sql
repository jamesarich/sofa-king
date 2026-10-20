CREATE TABLE admin_session (
    key TEXT PRIMARY KEY,
    csrf TEXT NOT NULL,
    expires_at TEXT NOT NULL
)
