CREATE TABLE offering_health(
    offering_id TEXT PRIMARY KEY,
    state_json TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE health_observation(
    offering_id TEXT NOT NULL REFERENCES offering_health(offering_id),
    incident_id TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    observation_json TEXT NOT NULL,
    PRIMARY KEY(offering_id, incident_id)
);
