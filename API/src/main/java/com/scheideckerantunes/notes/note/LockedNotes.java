package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * One authorization/precondition boundary for content, tags and editing leases.
 *
 * @author Carlos Scheidecker
 */
@Component
public class LockedNotes {
    private final NoteRepository notes;
    private final NoteWriteAccess writeAccess;
    private final EntityManager entityManager;

    public LockedNotes(NoteRepository notes, NoteWriteAccess writeAccess, EntityManager entityManager) {
        this.notes = notes;
        this.writeAccess = writeAccess;
        this.entityManager = entityManager;
    }

    public State require(UUID noteId, UUID actor, String ifMatch, boolean allowArchived) {
        var scope = notes.findScope(noteId).orElseThrow(ResourceNotFoundException::new);
        if (!allowArchived && scope.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        var access = writeAccess.lock(scope.getNotebookId(), actor);
        Note note;
        try {
            note = notes.findForModification(noteId).orElseThrow(ResourceNotFoundException::new);
            entityManager.refresh(note, LockModeType.PESSIMISTIC_WRITE);
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new NoteOperationException(NoteOperationException.Code.STALE_IF_MATCH);
        }
        if (!note.getNotebookId().equals(access.notebook().getId())
                || (!allowArchived && note.getArchivedAt() != null)) {
            throw new ResourceNotFoundException();
        }
        var state = new State(note, access);
        NoteEtags.require(state.etag(actor), ifMatch);
        return state;
    }

    /**
     * Only valid within the transaction retaining the team/notebook/note locks.
     *
     * @author Carlos Scheidecker
     */
    public record State(Note note, NoteWriteAccess.Access access) {
        public String etag(UUID actor) {
            return NoteEtags.value(note.getId(), note.getVersion(), note.getNotebookId(),
                    access.notebook().getVersion(), actor, access.role());
        }
    }
}
