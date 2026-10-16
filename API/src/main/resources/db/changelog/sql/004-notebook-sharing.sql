-- Author: Carlos Scheidecker
-- Additive upgrade: applied schema/demo changesets are not rewritten.
CREATE TABLE notebook_shares (
    notebook_id BINARY(16) NOT NULL,
    shared_with_team_id BINARY(16) NOT NULL,
    access_level VARCHAR(10) COLLATE utf8mb4_0900_as_cs NOT NULL,
    shared_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (notebook_id, shared_with_team_id),
    CONSTRAINT ck_notebook_share_access CHECK (access_level IN ('VIEWER', 'EDITOR')),
    CONSTRAINT fk_notebook_share_book FOREIGN KEY (notebook_id) REFERENCES notebooks(id) ON DELETE RESTRICT,
    CONSTRAINT fk_notebook_share_team FOREIGN KEY (shared_with_team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    INDEX idx_shares_recipient (shared_with_team_id, notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE activity_logs
    DROP CHECK ck_activity_action,
    ADD CONSTRAINT ck_activity_action CHECK (action IN (
        'NOTE_CREATED', 'NOTE_UPDATED', 'NOTE_ARCHIVED', 'NOTE_RESTORED', 'NOTE_TAGGED',
        'NOTE_UNTAGGED', 'COMMENT_CREATED', 'NOTEBOOK_CREATED', 'NOTEBOOK_UPDATED',
        'NOTEBOOK_ARCHIVED', 'TEAM_CREATED', 'MEMBER_ADDED', 'MEMBER_UPDATED', 'MEMBER_REMOVED',
        'NOTEBOOK_SHARED', 'NOTEBOOK_SHARE_UPDATED', 'NOTEBOOK_UNSHARED'
    ));
