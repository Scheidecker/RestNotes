package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.team.TeamRole;
import java.util.UUID;

/**
 * Internal current recipient path, including the team that must be locked before content writes.
 *
 * @author Carlos Scheidecker
 */
public record NotebookAccessPath(UUID teamId, ShareAccessLevel accessLevel, TeamRole membershipRole) {
    public TeamRole effectiveRole() {
        return accessLevel.cappedBy(membershipRole);
    }
}
