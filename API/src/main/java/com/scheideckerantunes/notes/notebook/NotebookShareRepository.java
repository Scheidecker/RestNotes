package com.scheideckerantunes.notes.notebook;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

/**
 * Internal grants and bounded batch access queries; not an exported Data REST repository.
 *
 * @author Carlos Scheidecker
 */
public interface NotebookShareRepository extends JpaRepository<NotebookShare, NotebookShareId> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from NotebookShare s where s.id = :id")
    Optional<NotebookShare> findForAdministration(NotebookShareId id);
    @Query("select new com.scheideckerantunes.notes.notebook.NotebookGrant("
            + "s.id.notebookId, s.accessLevel, m.role) from NotebookShare s "
            + "join TeamMember m on m.id.teamId = s.id.sharedWithTeamId "
            + "where s.id.notebookId in :notebookIds and m.id.userId = :userId")
    List<NotebookGrant> findCurrentGrants(Collection<UUID> notebookIds, UUID userId);

    @Query("select new com.scheideckerantunes.notes.notebook.NotebookPermission("
            + "t.id, t.name, s.accessLevel, s.sharedAt, s.updatedAt) from NotebookShare s "
            + "join Team t on t.id = s.id.sharedWithTeamId where s.id.notebookId = :notebookId order by t.name, t.id")
    Page<NotebookPermission> findPermissions(UUID notebookId, Pageable pageable);
}
