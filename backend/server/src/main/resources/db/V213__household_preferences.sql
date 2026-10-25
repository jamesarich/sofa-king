CREATE TABLE household_preferences (
    scope TEXT NOT NULL PRIMARY KEY DEFAULT 'household',
    preferences_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
)
