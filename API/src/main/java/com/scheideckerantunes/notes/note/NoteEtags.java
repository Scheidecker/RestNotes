package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.team.TeamRole;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A note validator also tracks its notebook's ACL/metadata version and current caller capability.
 *
 * @author Carlos Scheidecker
 */
public final class NoteEtags {
    private static final Pattern STRONG = Pattern.compile(
            "\"note:[0-9a-f-]{36}:[0-9]{1,19}:[0-9a-f-]{36}:[0-9]{1,19}:[0-9a-f-]{36}:(ADMIN|EDITOR|VIEWER)\"");

    private NoteEtags() {
    }

    public static String value(UUID id, long version, UUID notebookId,
            long notebookVersion, UUID actor, TeamRole role) {
        return "\"note:" + id + ":" + version + ":" + notebookId + ":"
                + notebookVersion + ":" + actor + ":" + role + "\"";
    }

    public static String from(NoteResponse response, UUID actor) {
        return value(response.id(), response.version(), response.notebookId(), response.notebookVersion(),
                actor, response.accessLevel());
    }

    public static void require(String expected, String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new NoteOperationException(NoteOperationException.Code.MISSING_IF_MATCH);
        }
        if (!STRONG.matcher(supplied).matches()) {
            throw new NoteOperationException(NoteOperationException.Code.INVALID_IF_MATCH);
        }
        if (!expected.equals(supplied)) {
            throw new NoteOperationException(NoteOperationException.Code.STALE_IF_MATCH);
        }
    }
}
