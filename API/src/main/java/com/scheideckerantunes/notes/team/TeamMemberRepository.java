package com.scheideckerantunes.notes.team;

import java.util.UUID;
import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

/**
 * Locking reads follow the team lock and observe committed roles, even under MySQL repeatable read.
 *
 * @author Carlos Scheidecker
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {
    Page<TeamMember> findByIdTeamId(UUID teamId, Pageable pageable);

    @Query("select new com.scheideckerantunes.notes.team.TeamMemberResponse("
            + "u.id, u.email, u.fullName, m.role, m.joinedAt) "
            + "from TeamMember m join UserAccount u on u.id = m.id.userId where m.id.teamId = :teamId "
            + "order by u.fullName, u.id")
    Page<TeamMemberResponse> findMemberDetails(UUID teamId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TeamMember m where m.id = :id")
    Optional<TeamMember> findForAdministration(TeamMemberId id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TeamMember m where m.id.teamId = :teamId and m.role = :role order by m.id.userId")
    List<TeamMember> findRoleHoldersForAdministration(UUID teamId, TeamRole role);
}
