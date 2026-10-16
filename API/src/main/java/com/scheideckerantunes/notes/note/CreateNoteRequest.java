package com.scheideckerantunes.notes.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Server derives notebook/team/creator IDs; pinning defaults to false.
 *
 * @author Carlos Scheidecker
 */
public record CreateNoteRequest(@NotBlank @Size(max = 255) String title,
        @NotNull @NoteContentSize String content, Boolean pinned) {
    public CreateNoteRequest {
        title = title == null ? null : title.strip();
        pinned = pinned == null ? Boolean.FALSE : pinned;
    }

    @Override
    public String toString() {
        return "CreateNoteRequest[title=REDACTED, content=REDACTED]";
    }
}
