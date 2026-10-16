package com.scheideckerantunes.notes.notebook;

import jakarta.validation.constraints.Size;

/**
 * Omitted/null fields are unchanged; an empty description explicitly clears its text.
 *
 * @author Carlos Scheidecker
 */
public record UpdateNotebookRequest(@Size(min = 1, max = 150) String title,
        @Size(max = 2000) String description) {
    public UpdateNotebookRequest {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}
