package com.scheideckerantunes.notes.auth;

import java.util.Locale;

/**
 * Shared normalization before validation, duplicate checks and password authentication.
 *
 * @author Carlos Scheidecker
 */
public final class EmailAddress {
    private EmailAddress() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
