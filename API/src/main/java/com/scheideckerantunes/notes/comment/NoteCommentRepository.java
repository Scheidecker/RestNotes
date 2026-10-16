package com.scheideckerantunes.notes.comment;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal discussion storage; callers must authorize the note before use.
 *
 * @author Carlos Scheidecker
 */
public interface NoteCommentRepository extends JpaRepository<NoteComment, UUID> {
    Page<NoteComment> findByNoteId(UUID noteId, Pageable pageable);
}
