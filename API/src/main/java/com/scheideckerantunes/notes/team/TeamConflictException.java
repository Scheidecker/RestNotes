package com.scheideckerantunes.notes.team;

/**
 * Only predefined business codes may enter the team conflict error contract.
 *
 * @author Carlos Scheidecker
 */
public class TeamConflictException extends RuntimeException {
    /**
     * Stable client-visible conflict codes, without user-supplied text.
     *
     * @author Carlos Scheidecker
     */
    public enum Code {
        MEMBER_ALREADY_EXISTS,
        LAST_TEAM_ADMIN
    }

    private final Code code;

    public TeamConflictException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
