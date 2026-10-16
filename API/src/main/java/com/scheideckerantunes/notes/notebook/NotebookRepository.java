package com.scheideckerantunes.notes.notebook;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

/**
 * Filters tenant access before pagination, including archived notebook exclusion.
 *
 * @author Carlos Scheidecker
 */
public interface NotebookRepository extends JpaRepository<Notebook, UUID> {

    @Query("select b from Notebook b where " + NotebookVisibility.READABLE)
    Page<Notebook> findVisibleTo(UUID userId, Pageable pageable);

    @Query("select b from Notebook b where " + NotebookVisibility.READABLE
            + " and (:teamId is null or b.teamId = :teamId) order by b.updatedAt desc, b.id")
    Page<Notebook> findVisibleTo(UUID userId, UUID teamId, Pageable pageable);

    @Query("select b from Notebook b where b.id = :notebookId and " + NotebookVisibility.READABLE)
    Optional<Notebook> findVisibleById(UUID notebookId, UUID userId);

    @Query("select b.teamId as teamId, b.archivedAt as archivedAt from Notebook b where b.id = :notebookId")
    Optional<OwnerInfo> findOwnerInfo(UUID notebookId);

    /**
     * Non-managed lookup avoids holding an obsolete entity version before a write lock.
     *
     * @author Carlos Scheidecker
     */
    interface OwnerInfo {
        UUID getTeamId();
        Instant getArchivedAt();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Notebook b where b.id = :notebookId")
    Optional<Notebook> findForAdministration(UUID notebookId);
}
