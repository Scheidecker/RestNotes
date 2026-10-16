package com.scheideckerantunes.notes.team;
import com.scheideckerantunes.notes.user.TeamAffiliation;

import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

/**
 * Database-paginated team discovery constrained by current membership.
 *
 * @author Carlos Scheidecker
 */
public interface TeamRepository extends JpaRepository<Team, UUID> {

    @Query("select t from Team t where exists "
            + "(select m from TeamMember m where m.id.teamId = t.id and m.id.userId = :userId)")
    Page<Team> findVisibleTo(UUID userId, Pageable pageable);

    @Query("select new com.scheideckerantunes.notes.user.TeamAffiliation(t.id, t.name, m.role) "
            + "from Team t join TeamMember m on m.id.teamId = t.id where m.id.userId = :userId")
    Page<TeamAffiliation> findAffiliations(UUID userId, Pageable pageable);

    @Query("select new com.scheideckerantunes.notes.team.TeamResponse("
            + "t.id, t.name, t.description, t.createdAt, m.role) "
            + "from Team t join TeamMember m on m.id.teamId = t.id where m.id.userId = :userId "
            + "order by t.name, t.id")
    Page<TeamResponse> findDetailsVisibleTo(UUID userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Team t where t.id = :teamId")
    Optional<Team> findForAdministration(UUID teamId);
}
