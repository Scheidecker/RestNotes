package com.scheideckerantunes.notes.note;
import com.scheideckerantunes.notes.notebook.NotebookVisibility;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

/**
 * Applies membership and both archive filters in SQL before pagination or counting.
 *
 * @author Carlos Scheidecker
 */
public interface NoteRepository extends JpaRepository<Note, UUID> {

    String VISIBLE = "n.archivedAt is null and exists "
            + "(select b from Notebook b where b.id = n.notebookId and " + NotebookVisibility.READABLE + ")";

    @Query("select n from Note n where " + VISIBLE)
    Page<Note> findVisibleTo(UUID userId, Pageable pageable);

    @Query("select n from Note n where n.id = :noteId and " + VISIBLE)
    Optional<Note> findVisibleById(UUID noteId, UUID userId);

    @Query("select new com.scheideckerantunes.notes.note.NoteSummary(n.id, n.notebookId, n.title, n.pinned, "
            + "n.createdBy, n.updatedBy, n.createdAt, n.updatedAt, n.version) from Note n "
            + "where n.notebookId = :notebookId and " + VISIBLE + " order by n.pinned desc, n.updatedAt desc, n.id")
    Page<NoteSummary> findSummaries(UUID notebookId, UUID userId, Pageable pageable);

    @Query("select n.notebookId as notebookId, n.archivedAt as archivedAt from Note n where n.id = :noteId")
    Optional<Scope> findScope(UUID noteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Note n where n.id = :noteId")
    Optional<Note> findForModification(UUID noteId);

    /**
     * Scalar lookups do not cache obsolete entity versions before locking.
     *
     * @author Carlos Scheidecker
     */
    interface Scope {
        UUID getNotebookId();
        Instant getArchivedAt();
    }
}
