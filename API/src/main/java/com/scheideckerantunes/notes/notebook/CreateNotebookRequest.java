package com.scheideckerantunes.notes.notebook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Notebook ownership is explicit and immutable; creator identity comes from the JWT.
 *
 * @author Carlos Scheidecker
 */
public record CreateNotebookRequest(@NotNull UUID teamId, @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description) {
    public CreateNotebookRequest {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}
