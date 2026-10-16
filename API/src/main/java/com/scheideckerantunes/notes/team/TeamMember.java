package com.scheideckerantunes.notes.team;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Membership association without an unnecessary bidirectional entity graph.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "team_members")
public class TeamMember {

    @EmbeddedId
    private TeamMemberId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 10)
    private TeamRole role;

    @Column(nullable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private Instant joinedAt;

    protected TeamMember() {
    }

    public TeamMember(UUID teamId, UUID userId, TeamRole role) {
        id = new TeamMemberId(teamId, userId);
        this.role = role;
        joinedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public TeamMemberId getId() {
        return id;
    }

    public TeamRole getRole() {
        return role;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
