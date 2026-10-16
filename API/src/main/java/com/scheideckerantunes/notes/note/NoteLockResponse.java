package com.scheideckerantunes.notes.note;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The secret is returned only on acquisition; renewal returns metadata alone.
 *
 * @author Carlos Scheidecker
 */
public record NoteLockResponse(UUID ownerId, Instant acquiredAt, Instant expiresAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) String token) {
    @Override
    public String toString() {
        return "NoteLockResponse[ownerId=" + ownerId + ", token=REDACTED]";
    }
}
