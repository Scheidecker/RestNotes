package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.team.TeamRole;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Actor/role scoping makes strong validators reflect caller-specific representations.
 *
 * @author Carlos Scheidecker
 */
public final class NotebookEtags {
    private static final Pattern STRONG = Pattern.compile(
            "\"[0-9a-f-]{36}:[0-9]{1,19}:[0-9a-f-]{36}:(ADMIN|EDITOR|VIEWER)\"");

    private NotebookEtags() {
    }

    public static String value(UUID notebookId, long version, UUID actorId, TeamRole role) {
        return "\"" + notebookId + ":" + version + ":" + actorId + ":" + role + "\"";
    }

    public static void require(String expected, String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new NotebookOperationException(NotebookOperationException.Code.MISSING_IF_MATCH);
        }
        if (!STRONG.matcher(supplied).matches()) {
            throw new NotebookOperationException(NotebookOperationException.Code.INVALID_IF_MATCH);
        }
        if (!expected.equals(supplied)) {
            throw new NotebookOperationException(NotebookOperationException.Code.STALE_IF_MATCH);
        }
    }
}
