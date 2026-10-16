package com.scheideckerantunes.notes.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * PUT replaces the complete title/content/pinned representation, preserving read-only tags.
 *
 * @author Carlos Scheidecker
 */
public record ReplaceNoteRequest(@NotBlank @Size(max = 255) String title,
        @NotNull @NoteContentSize String content, @NotNull Boolean pinned) {
    public ReplaceNoteRequest {
        title = title == null ? null : title.strip();
    }

    @Override
    public String toString() {
        return "ReplaceNoteRequest[title=REDACTED, content=REDACTED]";
    }
}
