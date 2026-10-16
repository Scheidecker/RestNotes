package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Markdown note. The composite notebook/team foreign key prevents tenant mismatch.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "notes")
public class Note extends VersionedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID notebookId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID teamId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false)
    private boolean pinned;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    private UUID createdBy;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "BINARY(16)")
    private UUID updatedBy;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant archivedAt;

    protected Note() {
    }

    public Note(UUID notebookId, UUID teamId, String title, String content, UUID authorId) {
        this.notebookId = notebookId;
        this.teamId = teamId;
        this.title = title;
        this.content = content;
        createdBy = authorId;
        updatedBy = authorId;
    }

    public void replaceContent(String title, String content, UUID editorId) {
        this.title = title;
        this.content = content;
        updatedBy = editorId;
    }

    public void archive(Instant at) {
        archivedAt = at;
    }

    public UUID getNotebookId() {
        return notebookId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public boolean isPinned() {
        return pinned;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
