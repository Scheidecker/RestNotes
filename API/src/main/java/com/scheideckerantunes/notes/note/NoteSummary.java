package com.scheideckerantunes.notes.note;

import java.time.Instant;
import java.util.UUID;

/**
 * Paginated note metadata omits potentially large Markdown bodies.
 *
 * @author Carlos Scheidecker
 */
public record NoteSummary(UUID id, UUID notebookId, String title, boolean pinned, UUID createdBy, UUID updatedBy,
        Instant createdAt, Instant updatedAt, long version) {
    @Override
    public String toString() {
        return "NoteSummary[id=" + id + ", title=REDACTED]";
    }
}
