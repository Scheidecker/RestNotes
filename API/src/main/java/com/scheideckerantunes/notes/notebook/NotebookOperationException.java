package com.scheideckerantunes.notes.notebook;

import org.springframework.http.HttpStatus;

/**
 * Predefined client errors without copying request headers or user-controlled text.
 *
 * @author Carlos Scheidecker
 */
public class NotebookOperationException extends RuntimeException {
    /**
     * Stable precondition and notebook policy codes.
     *
     * @author Carlos Scheidecker
     */
    public enum Code {
        MISSING_IF_MATCH(HttpStatus.PRECONDITION_REQUIRED, "A current notebook If-Match header is required"),
        INVALID_IF_MATCH(HttpStatus.BAD_REQUEST, "Supply one strong notebook ETag; wildcards are not supported"),
        STALE_IF_MATCH(HttpStatus.PRECONDITION_FAILED, "Notebook precondition failed; retrieve its current ETag"),
        EMPTY_PATCH(HttpStatus.BAD_REQUEST, "Provide a title or description to update"),
        OWNER_TEAM_SHARE(HttpStatus.CONFLICT, "The owning team already has inherited notebook access");

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

    public NotebookOperationException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
