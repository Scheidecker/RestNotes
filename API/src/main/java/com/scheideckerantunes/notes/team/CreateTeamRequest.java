package com.scheideckerantunes.notes.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a team without accepting client-selected IDs, ownership or memberships.
 *
 * @author Carlos Scheidecker
 */
public record CreateTeamRequest(@NotBlank @Size(max = 100) String name, @Size(max = 2000) String description) {
    public CreateTeamRequest {
        name = name == null ? null : name.strip();
        description = description == null ? null : description.strip();
    }
}
