package com.scheideckerantunes.notes.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One normalized name per mutation; the server derives the owning team.
 *
 * @author Carlos Scheidecker
 */
public record AddTagRequest(@NotBlank @Size(max = TagNames.MAX_LENGTH) @ValidTagName String name) {
    public AddTagRequest {
        name = TagNames.normalize(name);
    }

    @Override
    public String toString() {
        return "AddTagRequest[name=REDACTED]";
    }
}
