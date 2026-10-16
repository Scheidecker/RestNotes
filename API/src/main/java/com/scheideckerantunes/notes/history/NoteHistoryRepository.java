package com.scheideckerantunes.notes.history;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal snapshot storage; access checks belong to future history services.
 *
 * @author Carlos Scheidecker
 */
public interface NoteHistoryRepository extends JpaRepository<NoteHistory, UUID> {
    Page<NoteHistory> findByNoteId(UUID noteId, Pageable pageable);
}
