-- Author: Carlos Scheidecker
-- MySQL DDL is not universally transactional. Use forward fixes/backups, not implicit rollback.
CREATE TABLE users (
    id BINARY(16) NOT NULL PRIMARY KEY,
    email VARCHAR(255) COLLATE utf8mb4_0900_as_ci NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_normalized CHECK (
        BINARY email = BINARY LOWER(TRIM(email)) AND CHAR_LENGTH(email) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE teams (
    id BINARY(16) NOT NULL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_members (
    team_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    role VARCHAR(10) COLLATE utf8mb4_0900_as_cs NOT NULL DEFAULT 'VIEWER',
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (team_id, user_id),
    CONSTRAINT ck_member_role CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    CONSTRAINT fk_member_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_members_user (user_id, team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notebooks (
    id BINARY(16) NOT NULL PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT,
    created_by BINARY(16),
    archived_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_notebook_team UNIQUE (id, team_id),
    CONSTRAINT fk_notebook_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_notebook_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_notebooks_team (team_id, archived_at, updated_at DESC, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notes (
    id BINARY(16) NOT NULL PRIMARY KEY,
    notebook_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BINARY(16),
    updated_by BINARY(16),
    archived_at DATETIME(6),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_note_team UNIQUE (id, team_id),
    CONSTRAINT ck_note_content_size CHECK (OCTET_LENGTH(content) <= 262144),
    CONSTRAINT fk_note_notebook_team FOREIGN KEY (notebook_id, team_id)
        REFERENCES notebooks(id, team_id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_updater FOREIGN KEY (updated_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_notes_notebook (notebook_id, archived_at, updated_at DESC, id),
    INDEX idx_notes_team (team_id, archived_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tags (
    id BINARY(16) NOT NULL PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    normalized_name VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_tag_name UNIQUE (team_id, normalized_name),
    CONSTRAINT uq_tag_team UNIQUE (id, team_id),
    CONSTRAINT ck_tag_normalized CHECK (
        BINARY normalized_name = BINARY LOWER(TRIM(normalized_name))
        AND CHAR_LENGTH(normalized_name) > 0
    ),
    CONSTRAINT fk_tag_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE note_tags (
    note_id BINARY(16) NOT NULL,
    tag_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    PRIMARY KEY (note_id, tag_id),
    CONSTRAINT fk_note_tag_note FOREIGN KEY (note_id, team_id)
        REFERENCES notes(id, team_id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_tag_tag FOREIGN KEY (tag_id, team_id)
        REFERENCES tags(id, team_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE comments (
    id BINARY(16) NOT NULL PRIMARY KEY,
    note_id BINARY(16) NOT NULL,
    user_id BINARY(16),
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_comment_size CHECK (CHAR_LENGTH(content) BETWEEN 1 AND 10000),
    CONSTRAINT fk_comment_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_comment_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_comments_note_time (note_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE note_history (
    id BINARY(16) NOT NULL PRIMARY KEY,
    note_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    tags_snapshot JSON NOT NULL,
    modified_by BINARY(16),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_history_version UNIQUE (note_id, version_number),
    CONSTRAINT ck_history_version CHECK (version_number > 0),
    CONSTRAINT ck_history_content_size CHECK (OCTET_LENGTH(content) <= 262144),
    CONSTRAINT ck_history_tags CHECK (JSON_TYPE(tags_snapshot) = 'ARRAY' AND JSON_LENGTH(tags_snapshot) <= 20),
    CONSTRAINT fk_history_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_history_user FOREIGN KEY (modified_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE activity_logs (
    id BINARY(16) NOT NULL PRIMARY KEY,
    team_id BINARY(16) NOT NULL,
    user_id BINARY(16),
    action VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    entity_type VARCHAR(20) COLLATE utf8mb4_0900_as_cs NOT NULL,
    entity_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_activity_entity CHECK (entity_type IN ('note', 'notebook', 'comment', 'team', 'membership')),
    CONSTRAINT ck_activity_action CHECK (action IN (
        'NOTE_CREATED', 'NOTE_UPDATED', 'NOTE_ARCHIVED', 'NOTE_RESTORED', 'NOTE_TAGGED',
        'NOTE_UNTAGGED', 'COMMENT_CREATED', 'NOTEBOOK_CREATED', 'NOTEBOOK_UPDATED',
        'NOTEBOOK_ARCHIVED', 'TEAM_CREATED', 'MEMBER_ADDED', 'MEMBER_UPDATED', 'MEMBER_REMOVED'
    )),
    CONSTRAINT fk_activity_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE RESTRICT,
    CONSTRAINT fk_activity_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_activity_team_time (team_id, created_at DESC, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
