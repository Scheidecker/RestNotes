package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable optional editing leases remain independent from note content versions.
 *
 * @author Carlos Scheidecker
 */
@Service
@Transactional(readOnly = true)
public class NoteLockService {
    public static final Duration DURATION = Duration.ofMinutes(5);
    private final LockedNotes notes;
    private final NoteRepository repository;
    private final NoteLockStore store;
    private final NoteLocks locks;

    public NoteLockService(LockedNotes notes, NoteRepository repository, NoteLockStore store, NoteLocks locks) {
        this.notes = notes;
        this.repository = repository;
        this.store = store;
        this.locks = locks;
    }

    public NoteLockStatus status(UUID noteId, UUID actor) {
        repository.findVisibleById(noteId, actor).orElseThrow(ResourceNotFoundException::new);
        Instant now = store.now();
        return NoteLockStatus.from(store.find(noteId).orElse(null), now);
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public Result acquire(UUID noteId, UUID actor, String ifMatch, String token) {
        var state = notes.require(noteId, actor, ifMatch, false);
        NoteLockTokens.validate(token);
        Instant now = store.now();
        Instant expires = now.plus(DURATION);
        var lease = store.find(noteId).filter(value -> value.active(now));
        NoteLockResponse response;
        if (lease.isPresent()) {
            locks.requireHolder(lease.get(), actor, token);
            store.renew(noteId, expires);
            response = new NoteLockResponse(actor, lease.get().acquiredAt(), expires, null);
        } else {
            if (token != null) {
                throw new NoteOperationException(NoteOperationException.Code.STALE_LOCK_TOKEN);
            }
            String generated = NoteLockTokens.generate();
            store.acquire(noteId, actor, NoteLockTokens.digest(generated), now, expires);
            response = new NoteLockResponse(actor, now, expires, generated);
        }
        return new Result(state.etag(actor), response);
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public String release(UUID noteId, UUID actor, String ifMatch, String token) {
        var state = notes.require(noteId, actor, ifMatch, false);
        locks.requireWrite(noteId, actor, token);
        store.delete(noteId);
        return state.etag(actor);
    }

    /**
     * Controller handoff also redacts the token-bearing response.
     *
     * @author Carlos Scheidecker
     */
    public record Result(String etag, NoteLockResponse response) {
    }
}
