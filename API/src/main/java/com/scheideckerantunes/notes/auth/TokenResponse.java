package com.scheideckerantunes.notes.auth;

import java.time.Instant;

/**
 * Access-token response; do not log or cache this representation.
 *
 * @author Carlos Scheidecker
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {
    @Override
    public String toString() {
        return "TokenResponse[accessToken=REDACTED, tokenType=" + tokenType + ", expiresAt=" + expiresAt + "]";
    }
}
