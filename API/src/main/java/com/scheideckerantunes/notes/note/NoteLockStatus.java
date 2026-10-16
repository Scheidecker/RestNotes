package com.scheideckerantunes.notes.note;

import java.time.Instant;
import java.util.UUID;

/**
 * Reader-visible lease metadata never contains a credential or verifier.
 *
 * @author Carlos Scheidecker
 */
public record NoteLockStatus(boolean locked, UUID ownerId, Instant acquiredAt, Instant expiresAt) {
    public static NoteLockStatus from(NoteLockStore.Lease lease, Instant at) {
        return lease != null && lease.active(at)
                ? new NoteLockStatus(true, lease.ownerId(), lease.acquiredAt(), lease.expiresAt())
                : new NoteLockStatus(false, null, null, null);
    }
}
