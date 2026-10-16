package com.scheideckerantunes.notes.notebook;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Team grant serialized by the owning notebook aggregate's lock and version.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "notebook_shares")
public class NotebookShare {
    @EmbeddedId
    private NotebookShareId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private ShareAccessLevel accessLevel;

    @Column(nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant sharedAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    protected NotebookShare() {
    }

    public NotebookShare(UUID notebookId, UUID recipientTeamId, ShareAccessLevel accessLevel) {
        id = new NotebookShareId(notebookId, recipientTeamId);
        this.accessLevel = Objects.requireNonNull(accessLevel);
    }

    public void changeAccess(ShareAccessLevel accessLevel) {
        this.accessLevel = Objects.requireNonNull(accessLevel);
    }

    @PrePersist
    protected void created() {
        sharedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = sharedAt;
    }

    @PreUpdate
    protected void updated() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public NotebookShareId getId() {
        return id;
    }

    public ShareAccessLevel getAccessLevel() {
        return accessLevel;
    }

    public Instant getSharedAt() {
        return sharedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
