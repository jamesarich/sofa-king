CREATE TABLE probe_attempt(
    offering_id TEXT PRIMARY KEY,
    attempted_at TEXT NOT NULL
);
INSERT OR REPLACE INTO probe_attempt(offering_id, attempted_at)
    SELECT substr(key, 7), value FROM metadata WHERE substr(key, 1, 6) = 'probe:';
DELETE FROM metadata WHERE substr(key, 1, 6) = 'probe:';
CREATE INDEX health_observation_observed_at ON health_observation(observed_at);
CREATE INDEX offering_health_updated_at ON offering_health(updated_at)
