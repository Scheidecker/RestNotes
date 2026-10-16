package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.team.TeamRole;
import java.util.UUID;

/**
 * Internal batch projection for a currently valid shared access path.
 *
 * @author Carlos Scheidecker
 */
public record NotebookGrant(UUID notebookId, ShareAccessLevel accessLevel, TeamRole membershipRole) {
}
