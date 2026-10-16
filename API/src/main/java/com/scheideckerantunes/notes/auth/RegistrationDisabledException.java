package com.scheideckerantunes.notes.auth;

/**
 * Registration is an operator-controlled feature, disabled by default in UAT/prod.
 *
 * @author Carlos Scheidecker
 */
public class RegistrationDisabledException extends RuntimeException {
    public RegistrationDisabledException() {
        super("User registration is disabled");
    }
}
