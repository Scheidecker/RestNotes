package com.scheideckerantunes.notes.note;

import java.nio.charset.StandardCharsets;

/**
 * Matches the database content bound and permits escaped JSON within a bounded request.
 *
 * @author Carlos Scheidecker
 */
public final class NoteLimits {
    public static final int CONTENT_BYTES = 262144;
    public static final int BODY_BYTES = 2 * 1024 * 1024;
    public static final int TAGS = 20;

    private NoteLimits() {
    }

    public static boolean contentFits(String content) {
        return content == null || content.getBytes(StandardCharsets.UTF_8).length <= CONTENT_BYTES;
    }
}
