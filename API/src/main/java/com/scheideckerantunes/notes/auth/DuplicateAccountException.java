package com.scheideckerantunes.notes.auth;

/**
 * A normalized account identity already exists, including concurrent registrations.
 *
 * @author Carlos Scheidecker
 */
public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("An account with this email already exists");
    }
}
