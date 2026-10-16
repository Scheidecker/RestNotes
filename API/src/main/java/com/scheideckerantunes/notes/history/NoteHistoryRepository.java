package com.scheideckerantunes.notes.history;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Internal snapshot storage; access checks belong to future history services.
 *
 * @author Carlos Scheidecker
 */
public interface NoteHistoryRepository extends JpaRepository<NoteHistory, UUID> {
    Page<NoteHistory> findByNoteId(UUID noteId, Pageable pageable);

    @Query("select coalesce(max(h.versionNumber), 0) from NoteHistory h where h.noteId = :noteId")
    int findLatestVersion(UUID noteId);
}
