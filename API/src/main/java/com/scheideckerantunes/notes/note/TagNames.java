package com.scheideckerantunes.notes.note;

import java.util.Locale;

/**
 * Human-readable names exclude values that cannot safely round-trip as path segments.
 *
 * @author Carlos Scheidecker
 */
public final class TagNames {
    public static final int MAX_LENGTH = 50;

    private TagNames() {
    }

    public static String normalize(String name) {
        return name == null ? null : name.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean valid(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_LENGTH || name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.codePoints().noneMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT
                || Character.getType(code) == Character.LINE_SEPARATOR
                || Character.getType(code) == Character.PARAGRAPH_SEPARATOR
                || code == '/' || code == '\\' || code == ';' || code == '%');
    }

    public static String require(String name) {
        String normalized = normalize(name);
        if (!valid(normalized)) {
            throw new NoteOperationException(NoteOperationException.Code.INVALID_TAG_NAME);
        }
        return normalized;
    }
}
