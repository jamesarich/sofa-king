ALTER TABLE recording_rule ADD COLUMN match_kind TEXT NOT NULL DEFAULT 'title';
ALTER TABLE recording_rule ADD COLUMN terms_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE recording_rule ADD COLUMN categories_json TEXT NOT NULL DEFAULT '[]';
