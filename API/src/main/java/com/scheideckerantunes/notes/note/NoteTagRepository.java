package com.scheideckerantunes.notes.note;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

/**
 * Internal tag association storage with tenant constraints enforced by MySQL.
 *
 * @author Carlos Scheidecker
 */
public interface NoteTagRepository extends JpaRepository<NoteTag, NoteTagId> {
    @Query("select t.normalizedName from NoteTag association join Tag t on t.id = association.id.tagId "
            + "where association.id.noteId = :noteId order by t.normalizedName, t.id")
    List<String> findNames(UUID noteId, Pageable pageable);
}
