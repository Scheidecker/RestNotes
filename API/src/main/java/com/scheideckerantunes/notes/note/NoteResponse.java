package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.team.TeamRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bounded full note representation; never log its Markdown or title.
 *
 * @author Carlos Scheidecker
 */
public record NoteResponse(UUID id, UUID notebookId, UUID teamId, String title, String content, boolean pinned,
        List<String> tags, UUID createdBy, UUID updatedBy, Instant createdAt, Instant updatedAt,
        long version, long notebookVersion, TeamRole accessLevel) {
    public static NoteResponse from(Note note, long notebookVersion, TeamRole role, List<String> tags) {
        return new NoteResponse(note.getId(), note.getNotebookId(), note.getTeamId(),
                note.getTitle(), note.getContent(), note.isPinned(), List.copyOf(tags),
                note.getCreatedBy(), note.getUpdatedBy(), note.getCreatedAt(),
                note.getUpdatedAt(), note.getVersion(), notebookVersion, role);
    }

    @Override
    public String toString() {
        return "NoteResponse[id=" + id + ", title=REDACTED, content=REDACTED]";
    }
}
