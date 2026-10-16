package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.team.TeamRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Scalar metadata and caller-specific effective role; no embedded grants or notes.
 *
 * @author Carlos Scheidecker
 */
public record NotebookResponse(UUID id, UUID teamId, String title, String description, UUID createdBy,
        Instant createdAt, Instant updatedAt, long version, TeamRole accessLevel) {
    public static NotebookResponse from(Notebook notebook, TeamRole role) {
        return new NotebookResponse(notebook.getId(), notebook.getTeamId(), notebook.getTitle(),
                notebook.getDescription(), notebook.getCreatedBy(), notebook.getCreatedAt(),
                notebook.getUpdatedAt(), notebook.getVersion(), role);
    }
}
