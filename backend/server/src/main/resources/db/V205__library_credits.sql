ALTER TABLE library_intro ADD COLUMN tail_fingerprint BLOB;
ALTER TABLE library_intro ADD COLUMN tail_start_ms INTEGER;
ALTER TABLE library_intro ADD COLUMN credits_start_ms INTEGER;
ALTER TABLE library_intro ADD COLUMN credits_end_ms INTEGER
