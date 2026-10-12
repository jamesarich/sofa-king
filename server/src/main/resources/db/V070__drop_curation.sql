DROP TABLE curation_rule;
DROP TABLE curation_entry_state;
DROP TABLE curation_adopted;
DELETE FROM metadata WHERE key = 'curation:settings';
DROP INDEX curation_change_applied_at;
DELETE FROM curation_change WHERE json_extract(action_json, '$.kind') IS NOT 'policy';
ALTER TABLE curation_change RENAME TO policy_change;
ALTER TABLE policy_change RENAME COLUMN action_json TO change_json;
ALTER TABLE lineup_entry DROP COLUMN pinned
