package com.scheideckerantunes.notes.note;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Unique note/tag association.
 *
 * @author Carlos Scheidecker
 */
@Embeddable
public record NoteTagId(
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID noteId,
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID tagId)
        implements Serializable {
}
