package com.scheideckerantunes.notes.user;

import com.scheideckerantunes.notes.team.TeamRole;
import java.util.UUID;

/**
 * Current team affiliation, always queried from the database rather than JWT roles.
 *
 * @author Carlos Scheidecker
 */
public record TeamAffiliation(UUID teamId, String name, TeamRole role) {
}
