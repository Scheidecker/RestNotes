package com.scheideckerantunes.notes.notebook;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One grant per notebook and recipient team.
 *
 * @author Carlos Scheidecker
 */
@Embeddable
public record NotebookShareId(
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID notebookId,
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID sharedWithTeamId)
        implements Serializable {
}
