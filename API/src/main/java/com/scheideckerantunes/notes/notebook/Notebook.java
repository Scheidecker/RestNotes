package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Notebook owned by exactly one team; archive does not destroy its notes/history.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "notebooks")
public class Notebook extends VersionedEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID teamId;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    private UUID createdBy;

    @Column(columnDefinition = "DATETIME(6)")
    private Instant archivedAt;

    protected Notebook() {
    }

    public Notebook(UUID teamId, String title, String description, UUID createdBy) {
        this.teamId = teamId;
        this.title = title;
        this.description = description;
        this.createdBy = createdBy;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public boolean updateMetadata(String title, String description) {
        String nextTitle = title == null ? this.title : title;
        String nextDescription = description == null ? this.description : description;
        if (Objects.equals(this.title, nextTitle) && Objects.equals(this.description, nextDescription)) {
            return false;
        }
        this.title = nextTitle;
        this.description = nextDescription;
        return true;
    }

    public void permissionsChanged() {
        markUpdated();
    }

    public boolean archive(Instant at) {
        if (archivedAt != null) {
            return false;
        }
        archivedAt = at.truncatedTo(ChronoUnit.MICROS);
        return true;
    }
}
