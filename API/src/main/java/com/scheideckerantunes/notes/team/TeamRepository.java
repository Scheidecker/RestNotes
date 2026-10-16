package com.scheideckerantunes.notes.team;
import com.scheideckerantunes.notes.user.TeamAffiliation;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
