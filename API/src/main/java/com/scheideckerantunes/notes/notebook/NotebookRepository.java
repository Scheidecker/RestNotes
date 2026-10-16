package com.scheideckerantunes.notes.notebook;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Filters tenant access before pagination, including archived notebook exclusion.
 *
 * @author Carlos Scheidecker
 */
public interface NotebookRepository extends JpaRepository<Notebook, UUID> {

    @Query("select n from Notebook n where n.archivedAt is null and exists "
            + "(select m from TeamMember m where m.id.teamId = n.teamId and m.id.userId = :userId)")
    Page<Notebook> findVisibleTo(UUID userId, Pageable pageable);
}
