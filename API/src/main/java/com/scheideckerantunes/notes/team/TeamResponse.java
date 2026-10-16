package com.scheideckerantunes.notes.team;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe scalar team representation with the caller's current role.
 *
 * @author Carlos Scheidecker
 */
public record TeamResponse(UUID id, String name, String description, Instant createdAt, TeamRole role) {
    public static TeamResponse from(Team team, TeamRole role) {
        return new TeamResponse(team.getId(), team.getName(), team.getDescription(), team.getCreatedAt(), role);
    }
}
