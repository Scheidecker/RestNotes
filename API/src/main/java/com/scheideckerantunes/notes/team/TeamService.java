package com.scheideckerantunes.notes.team;

import com.scheideckerantunes.notes.activity.ActivityLog;
import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.common.PageResponse;
import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every membership write locks its team first; authorization and invariants are checked inside that transaction.
 *
 * @author Carlos Scheidecker
 */
@Service
@Transactional(readOnly = true)
public class TeamService {
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserAccountRepository users;
    private final ActivityLogRepository activity;
    private final EntityManager entityManager;

    public TeamService(TeamRepository teams, TeamMemberRepository members, UserAccountRepository users,
            ActivityLogRepository activity, EntityManager entityManager) {
        this.teams = teams;
        this.members = members;
        this.users = users;
        this.activity = activity;
        this.entityManager = entityManager;
    }

    @Transactional
    public TeamResponse create(UUID actorId, CreateTeamRequest request) {
        users.findById(actorId).orElseThrow(ResourceNotFoundException::new);
        Team team = teams.saveAndFlush(new Team(request.name(), request.description()));
        members.saveAndFlush(new TeamMember(team.getId(), actorId, TeamRole.ADMIN));
        activity.save(new ActivityLog(team.getId(), actorId, "TEAM_CREATED", "team", team.getId()));
        return TeamResponse.from(team, TeamRole.ADMIN);
    }

    public PageResponse<TeamResponse> list(UUID actorId, int page, int size) {
        return PageResponse.from(teams.findDetailsVisibleTo(actorId, PageRequest.of(page, size)));
    }

    public TeamResponse detail(UUID teamId, UUID actorId) {
        TeamMember caller = requireMember(teamId, actorId);
        Team team = teams.findById(teamId).orElseThrow(ResourceNotFoundException::new);
        return TeamResponse.from(team, caller.getRole());
    }

    public PageResponse<TeamMemberResponse> listMembers(UUID teamId, UUID actorId, int page, int size) {
        requireMember(teamId, actorId);
        return PageResponse.from(members.findMemberDetails(teamId, PageRequest.of(page, size)));
    }

    @Transactional(timeout = 15)
    public TeamMemberResponse addMember(UUID teamId, UUID actorId, AddTeamMemberRequest request) {
        requireAdminLocked(teamId, actorId);
        UserAccount account = users.findByEmail(request.email()).orElseThrow(ResourceNotFoundException::new);
        TeamMemberId id = new TeamMemberId(teamId, account.getId());
        if (lockedMembership(id).isPresent()) {
            throw new TeamConflictException(TeamConflictException.Code.MEMBER_ALREADY_EXISTS);
        }
        TeamMember member = members.saveAndFlush(new TeamMember(teamId, account.getId(), request.role()));
        recordMembershipChange(teamId, actorId, account.getId(), "MEMBER_ADDED");
        return TeamMemberResponse.from(account, member);
    }

    @Transactional(timeout = 15)
    public TeamMemberResponse updateMember(UUID teamId, UUID actorId, UUID userId, UpdateTeamMemberRequest request) {
        requireAdminLocked(teamId, actorId);
        TeamMember member = lockedMembership(new TeamMemberId(teamId, userId))
                .orElseThrow(ResourceNotFoundException::new);
        if (member.getRole() != request.role()) {
            if (request.role() != TeamRole.ADMIN) {
                protectLastAdmin(teamId, member);
            }
            member.changeRole(request.role());
            members.flush();
            recordMembershipChange(teamId, actorId, userId, "MEMBER_UPDATED");
        }
        return TeamMemberResponse.from(users.findById(userId).orElseThrow(ResourceNotFoundException::new), member);
    }

    @Transactional(timeout = 15)
    public void removeMember(UUID teamId, UUID actorId, UUID userId) {
        requireAdminLocked(teamId, actorId);
        Optional<TeamMember> target = lockedMembership(new TeamMemberId(teamId, userId));
        if (target.isEmpty()) {
            return;
        }
        protectLastAdmin(teamId, target.get());
        members.delete(target.get());
        members.flush();
        recordMembershipChange(teamId, actorId, userId, "MEMBER_REMOVED");
    }

    private TeamMember requireMember(UUID teamId, UUID actorId) {
        return members.findById(new TeamMemberId(teamId, actorId)).orElseThrow(ResourceNotFoundException::new);
    }

    private void requireAdminLocked(UUID teamId, UUID actorId) {
        teams.findForAdministration(teamId).orElseThrow(ResourceNotFoundException::new);
        TeamMember caller = lockedMembership(new TeamMemberId(teamId, actorId))
                .orElseThrow(ResourceNotFoundException::new);
        if (caller.getRole() != TeamRole.ADMIN) {
            throw new AccessDeniedException("Team administration requires the current ADMIN role");
        }
    }

    private Optional<TeamMember> lockedMembership(TeamMemberId id) {
        return members.findForAdministration(id).map(member -> {
            // Refresh also defeats an already-managed stale role in an enclosing transaction.
            entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
            return member;
        });
    }

    private void protectLastAdmin(UUID teamId, TeamMember member) {
        if (member.getRole() == TeamRole.ADMIN
                && members.findRoleHoldersForAdministration(teamId, TeamRole.ADMIN).size() <= 1) {
            throw new TeamConflictException(TeamConflictException.Code.LAST_TEAM_ADMIN);
        }
    }

    private void recordMembershipChange(UUID teamId, UUID actorId, UUID targetUserId, String action) {
        activity.save(new ActivityLog(teamId, actorId, action, "membership", targetUserId));
    }
}
