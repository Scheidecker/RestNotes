package com.scheideckerantunes.notes.activity;

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
 * Immutable content activity metadata; never stores note bodies or credentials.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Immutable
@Table(name = "activity_logs")
public class ActivityLog extends UuidEntity {

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID teamId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(updatable = false, columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(nullable = false, updatable = false, length = 20)
    private String entityType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID entityId;

    @Column(nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    protected ActivityLog() {
    }

    public ActivityLog(UUID teamId, UUID userId, String action, String entityType, UUID entityId) {
        this.teamId = teamId;
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
