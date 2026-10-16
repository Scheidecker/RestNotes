package com.scheideckerantunes.notes.team;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Unique team/user membership identifier.
 *
 * @author Carlos Scheidecker
 */
@Embeddable
public record TeamMemberId(
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID teamId,
        @JdbcTypeCode(SqlTypes.BINARY) @Column(columnDefinition = "BINARY(16)") UUID userId)
        implements Serializable {
}
