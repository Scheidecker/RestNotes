-- Author: Carlos Scheidecker
-- The authorized note row serializes acquisition even when no lease row exists.
CREATE TABLE note_locks (
    note_id BINARY(16) NOT NULL,
    owner_id BINARY(16) NOT NULL,
    token_digest VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    acquired_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (note_id),
    CONSTRAINT fk_note_lock_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE RESTRICT,
    CONSTRAINT fk_note_lock_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_note_lock_deadline CHECK (expires_at > acquired_at),
    CONSTRAINT ck_note_lock_digest CHECK (REGEXP_LIKE(token_digest, '^[0-9a-f]{64}$', 'c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
