package com.scheideckerantunes.notes.history;

import com.scheideckerantunes.notes.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only application snapshot, independent of the live note's row version.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Immutable
@Table(name = "note_history")
public class NoteHistory extends UuidEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID noteId;

    @Column(nullable = false, updatable = false)
    private int versionNumber;

    @Column(nullable = false, updatable = false, length = 255)
    private String title;

    @Column(nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private boolean pinned;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "JSON")
    private String tagsSnapshot;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    private UUID modifiedBy;

    @Column(nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected NoteHistory() {
    }

    public NoteHistory(UUID noteId, int versionNumber, String title, String content,
            boolean pinned, String tagsSnapshot, UUID modifiedBy) {
        this.noteId = noteId;
        this.versionNumber = versionNumber;
        this.title = title;
        this.content = content;
        this.pinned = pinned;
        this.tagsSnapshot = tagsSnapshot;
        this.modifiedBy = modifiedBy;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getNoteId() {
        return noteId;
    }

    public int getVersionNumber() {
        return versionNumber;
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

    public String getTagsSnapshot() {
        return tagsSnapshot;
    }

    public UUID getModifiedBy() {
        return modifiedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
