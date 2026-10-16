package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.team.TeamRole;

/**
 * External teams can never acquire notebook ADMIN rights through sharing.
 *
 * @author Carlos Scheidecker
 */
public enum ShareAccessLevel {
    VIEWER,
    EDITOR;

    public TeamRole cappedBy(TeamRole membershipRole) {
        return this == EDITOR && membershipRole != TeamRole.VIEWER ? TeamRole.EDITOR : TeamRole.VIEWER;
    }
}
