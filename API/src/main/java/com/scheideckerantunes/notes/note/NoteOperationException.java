package com.scheideckerantunes.notes.note;

import org.springframework.http.HttpStatus;

/**
 * Fixed client error codes never echo Markdown, tokens or conditional headers.
 *
 * @author Carlos Scheidecker
 */
public class NoteOperationException extends RuntimeException {
    /**
     * Client-visible note request/precondition outcomes.
     *
     * @author Carlos Scheidecker
     */
    public enum Code {
        MISSING_IF_MATCH(HttpStatus.PRECONDITION_REQUIRED, "A current note If-Match header is required"),
        INVALID_IF_MATCH(HttpStatus.BAD_REQUEST, "Supply one strong note ETag; wildcards are not supported"),
        STALE_IF_MATCH(HttpStatus.PRECONDITION_FAILED, "Note precondition failed; retrieve its current ETag"),
        EMPTY_PATCH(HttpStatus.BAD_REQUEST, "Provide title, content or pinned to update"),
        NULL_PATCH_FIELD(HttpStatus.BAD_REQUEST, "Note patch fields cannot be explicitly null"),
        CONTENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Note content exceeds 262144 UTF-8 bytes"),
        BODY_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Note JSON body exceeds 2097152 bytes"),
        ACCESS_CHANGED(HttpStatus.CONFLICT, "Notebook access changed; retrieve it and retry"),
        TAG_LIMIT(HttpStatus.CONFLICT, "A note supports at most 20 tags"),
        INVALID_TAG_NAME(HttpStatus.BAD_REQUEST, "Tag name is blank, too long or not safely routable"),
        NOTE_LOCKED(HttpStatus.LOCKED, "An active editing lease requires its owner's lock token"),
        STALE_LOCK_TOKEN(HttpStatus.CONFLICT, "Lock token is stale or expired; retrieve current lock status"),
        INVALID_LOCK_TOKEN(HttpStatus.BAD_REQUEST, "Supply one valid opaque note lock token");

        private final HttpStatus status;
        private final String detail;

        Code(HttpStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }

        public HttpStatus status() {
            return status;
        }

        public String detail() {
            return detail;
        }
    }

    private final Code code;

    public NoteOperationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
