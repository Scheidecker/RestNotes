package com.scheideckerantunes.notes.comment;

import com.scheideckerantunes.notes.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Bounded plain-text feedback; mention parsing is deliberately not implemented.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "comments")
public class NoteComment extends AuditedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID noteId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    protected NoteComment() {
    }

    public NoteComment(UUID noteId, UUID userId, String content) {
        this.noteId = noteId;
        this.userId = userId;
        this.content = content;
    }

    public UUID getNoteId() {
        return noteId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }
}
