package com.scheideckerantunes.notes.notebook;

import java.time.Instant;
import java.util.UUID;

/**
 * Sharing metadata visible only to owner-team ADMINs.
 *
 * @author Carlos Scheidecker
 */
public record NotebookPermission(UUID teamId, String teamName, ShareAccessLevel accessLevel,
        Instant sharedAt, Instant updatedAt) {
}
