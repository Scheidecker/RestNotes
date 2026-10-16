-- Author: Carlos Scheidecker
-- Public development fixtures only. Executed once by the explicit @demo changeset.
SET @seed_time = '2026-10-16 00:00:00.000000';
SET @eng_user = UUID_TO_BIN('10000000-0000-4000-8000-000000000001');
SET @mkt_user = UUID_TO_BIN('10000000-0000-4000-8000-000000000002');
SET @eng_team = UUID_TO_BIN('20000000-0000-4000-8000-000000000001');
SET @mkt_team = UUID_TO_BIN('20000000-0000-4000-8000-000000000002');
SET @eng_book = UUID_TO_BIN('30000000-0000-4000-8000-000000000001');
SET @mkt_book = UUID_TO_BIN('30000000-0000-4000-8000-000000000002');
SET @eng_note = UUID_TO_BIN('40000000-0000-4000-8000-000000000001');
SET @mkt_note = UUID_TO_BIN('40000000-0000-4000-8000-000000000002');

INSERT INTO users (id, email, password_hash, full_name, created_at, updated_at) VALUES
(@eng_user, 'engineering.admin@scheideckerantunes.com',
 '$2b$10$DmZzgfSt9tOGqtFYnxd5dOaprrBvqD27cuujcM3MQf0fLhVzVrPYW',
 'Engineering Admin', @seed_time, @seed_time),
(@mkt_user, 'marketing.admin@scheideckerantunes.com',
 '$2b$10$4FcAuXHAi2fSxgoTsw0f.ez88ahXOuU9ngG8/y80wgim0BKKwuRge',
 'Marketing Admin', @seed_time, @seed_time);

INSERT INTO teams (id, name, description, version, created_at, updated_at) VALUES
(@eng_team, 'Engineering', 'Engineering team workspace', 0, @seed_time, @seed_time),
(@mkt_team, 'Marketing', 'Marketing team workspace', 0, @seed_time, @seed_time);

INSERT INTO team_members (team_id, user_id, role, joined_at) VALUES
(@eng_team, @eng_user, 'ADMIN', @seed_time),
(@mkt_team, @mkt_user, 'ADMIN', @seed_time);

INSERT INTO notebooks
(id, team_id, title, description, created_by, version, created_at, updated_at) VALUES
(@eng_book, @eng_team, 'Engineering', 'Engineering project notes', @eng_user, 0, @seed_time, @seed_time),
(@mkt_book, @mkt_team, 'Marketing', 'Marketing campaign notes', @mkt_user, 0, @seed_time, @seed_time);

INSERT INTO notes
(id, notebook_id, team_id, title, content, pinned, created_by, updated_by, version, created_at, updated_at) VALUES
(@eng_note, @eng_book, @eng_team, 'Engineering onboarding',
 '# Engineering onboarding\n\nCapture architecture decisions and engineering project notes here.',
 FALSE, @eng_user, @eng_user, 0, @seed_time, @seed_time),
(@mkt_note, @mkt_book, @mkt_team, 'Marketing campaign kickoff',
 '# Marketing campaign kickoff\n\nCapture audience research and campaign milestones here.',
 FALSE, @mkt_user, @mkt_user, 0, @seed_time, @seed_time);

INSERT INTO note_history
(id, note_id, version_number, title, content, pinned, tags_snapshot, modified_by, created_at)
SELECT UUID_TO_BIN('50000000-0000-4000-8000-000000000001'),
 id, 1, title, content, pinned, JSON_ARRAY(), updated_by, created_at FROM notes WHERE id = @eng_note;
INSERT INTO note_history
(id, note_id, version_number, title, content, pinned, tags_snapshot, modified_by, created_at)
SELECT UUID_TO_BIN('50000000-0000-4000-8000-000000000002'),
 id, 1, title, content, pinned, JSON_ARRAY(), updated_by, created_at FROM notes WHERE id = @mkt_note;

INSERT INTO activity_logs (id, team_id, user_id, action, entity_type, entity_id, created_at) VALUES
(UUID_TO_BIN('60000000-0000-4000-8000-000000000001'),
 @eng_team, @eng_user, 'NOTE_CREATED', 'note', @eng_note, @seed_time),
(UUID_TO_BIN('60000000-0000-4000-8000-000000000002'),
 @mkt_team, @mkt_user, 'NOTE_CREATED', 'note', @mkt_note, @seed_time);
