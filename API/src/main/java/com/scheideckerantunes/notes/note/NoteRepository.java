package com.scheideckerantunes.notes.note;
import com.scheideckerantunes.notes.notebook.NotebookVisibility;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
