package com.scheideckerantunes.notes.note;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal tag association storage with tenant constraints enforced by MySQL.
 *
 * @author Carlos Scheidecker
 */
public interface NoteTagRepository extends JpaRepository<NoteTag, NoteTagId> {
}
