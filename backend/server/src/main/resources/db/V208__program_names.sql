ALTER TABLE guide_programme RENAME TO guide_program;
DROP INDEX guide_programme_window;
CREATE INDEX guide_program_window ON guide_program(guide_id, generation, channel_id, ends_at);
UPDATE recording SET state = 'canceled' WHERE state = 'cancelled'
