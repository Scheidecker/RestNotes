package com.scheideckerantunes.notes.team;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal membership storage; service authorization is added with Phase 3.
 *
 * @author Carlos Scheidecker
 */
public interface TeamMemberRepository extends JpaRepository<TeamMember, TeamMemberId> {
    Page<TeamMember> findByIdTeamId(UUID teamId, Pageable pageable);
}
