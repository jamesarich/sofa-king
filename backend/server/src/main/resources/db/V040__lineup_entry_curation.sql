ALTER TABLE lineup_entry ADD COLUMN pinned TEXT NOT NULL DEFAULT '';
ALTER TABLE lineup_entry ADD COLUMN created_by TEXT NOT NULL DEFAULT 'manual';
ALTER TABLE lineup_entry ADD COLUMN auto_disabled_at TEXT;
ALTER TABLE lineup_entry ADD COLUMN auto_disabled_reason TEXT
