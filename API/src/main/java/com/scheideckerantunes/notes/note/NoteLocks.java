package com.scheideckerantunes.notes.note;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * All callers must hold the authorized note lock before checking a lease.
 *
 * @author Carlos Scheidecker
 */
@Component
public class NoteLocks {
    private final NoteLockStore store;

    public NoteLocks(NoteLockStore store) {
        this.store = store;
    }

    public void requireWrite(UUID noteId, UUID actor, String token) {
        NoteLockTokens.validate(token);
        Instant now = store.now();
        var lease = store.find(noteId).filter(value -> value.active(now));
        if (lease.isEmpty()) {
            if (token != null) {
                throw new NoteOperationException(NoteOperationException.Code.STALE_LOCK_TOKEN);
            }
            return;
        }
        requireHolder(lease.get(), actor, token);
    }

    public void requireHolder(NoteLockStore.Lease lease, UUID actor, String token) {
        if (!lease.ownerId().equals(actor) || token == null) {
            throw new NoteOperationException(NoteOperationException.Code.NOTE_LOCKED);
        }
        if (!NoteLockTokens.matches(token, lease.digest())) {
            throw new NoteOperationException(NoteOperationException.Code.STALE_LOCK_TOKEN);
        }
    }

    public void archived(UUID noteId) {
        store.delete(noteId);
    }
}
