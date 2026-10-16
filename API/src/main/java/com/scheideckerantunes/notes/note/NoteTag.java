package com.scheideckerantunes.notes.note;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Shared team column participates in both foreign keys, preventing cross-team links.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "note_tags")
public class NoteTag {

    @EmbeddedId
    private NoteTagId id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID teamId;

    protected NoteTag() {
    }

    public NoteTag(UUID noteId, UUID tagId, UUID teamId) {
        id = new NoteTagId(noteId, tagId);
        this.teamId = teamId;
    }

    public NoteTagId getId() {
        return id;
    }

    public UUID getTeamId() {
        return teamId;
    }
}
