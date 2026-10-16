package com.scheideckerantunes.notes.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Application-owned UTC timestamps with MySQL microsecond precision.
 *
 * @author Carlos Scheidecker
 */
@MappedSuperclass
public abstract class AuditedEntity extends UuidEntity {

    @Column(nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    @Column(nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    @PrePersist
    protected void recordCreation() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void recordUpdate() {
        markUpdated();
    }

    protected void markUpdated() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt != null && !now.isAfter(updatedAt) ? updatedAt.plus(1, ChronoUnit.MICROS) : now;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
