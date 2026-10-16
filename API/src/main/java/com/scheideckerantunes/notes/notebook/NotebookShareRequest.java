package com.scheideckerantunes.notes.notebook;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One team grant per mutation keeps ACL updates bounded and explicit.
 *
 * @author Carlos Scheidecker
 */
public record NotebookShareRequest(@NotNull UUID teamId, @NotNull ShareAccessLevel accessLevel) {
}
