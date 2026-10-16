package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Team-scoped normalized label; identical names in different teams are independent.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "tags")
public class Tag extends AuditedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID teamId;

    @Column(nullable = false, length = 50)
    private String normalizedName;

    protected Tag() {
    }

    public Tag(UUID teamId, String name) {
        this.teamId = teamId;
        normalizedName = name.strip().toLowerCase(Locale.ROOT);
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getNormalizedName() {
        return normalizedName;
    }
}
