package com.scheideckerantunes.notes.team;

import jakarta.validation.constraints.NotNull;

/**
 * Explicit role update; null/missing roles never silently reset permissions.
 *
 * @author Carlos Scheidecker
 */
public record UpdateTeamMemberRequest(@NotNull TeamRole role) {
}
