package com.scheideckerantunes.notes.notebook;

/**
 * One SQL visibility policy, reused by notebook and note queries before pagination/counting.
 *
 * @author Carlos Scheidecker
 */
public final class NotebookVisibility {
    public static final String READABLE = "b.archivedAt is null and (exists "
            + "(select om from TeamMember om where om.id.teamId = b.teamId and om.id.userId = :userId) "
            + "or exists (select s from NotebookShare s join TeamMember sm "
            + "on sm.id.teamId = s.id.sharedWithTeamId where s.id.notebookId = b.id and sm.id.userId = :userId))";

    private NotebookVisibility() {
    }
}
