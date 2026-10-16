package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.CreateTeamRequest;
import com.scheideckerantunes.notes.team.Team;
import com.scheideckerantunes.notes.team.TeamConflictException;
import com.scheideckerantunes.notes.team.TeamMember;
import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.team.UpdateTeamMemberRequest;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pure service tests verify that permissions and admin invariants precede any write.
 *
 * @author Carlos Scheidecker
 */
class TeamServiceTest {
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final ActivityLogRepository activity = mock(ActivityLogRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final TeamService service = new TeamService(teams, members, users, activity, entityManager);
    private final UUID teamId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final TeamMemberId actorKey = new TeamMemberId(teamId, actorId);
    private final Team team = mock(Team.class);

    @BeforeEach
    void setUp() {
        when(team.getId()).thenReturn(teamId);
        when(teams.findForAdministration(teamId)).thenReturn(Optional.of(team));
    }

    @Test
    void creationAssignsAdminAndRecordsTheTeamAtomically() {
        when(users.findById(actorId)).thenReturn(Optional.of(mock(UserAccount.class)));
        when(teams.saveAndFlush(any(Team.class))).thenReturn(team);
        var result = service.create(actorId, new CreateTeamRequest(" New Team ", " Description "));
        ArgumentCaptor<TeamMember> captured = ArgumentCaptor.forClass(TeamMember.class);
        verify(members).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getId()).isEqualTo(actorKey);
        assertThat(captured.getValue().getRole()).isEqualTo(TeamRole.ADMIN);
        assertThat(result.role()).isEqualTo(TeamRole.ADMIN);
        verify(activity).save(any());
    }

    @Test
    void outsiderCannotProbeTargetUsersOrMutateMemberships() {
        when(members.findForAdministration(actorKey)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addMember(teamId, actorId,
                new AddTeamMemberRequest("other@scheideckerantunes.com", TeamRole.VIEWER)))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(users, activity);
        verify(members, never()).saveAndFlush(any());
    }

    @Test
    void currentViewerCannotAdministerTheTeam() {
        when(members.findForAdministration(actorKey))
                .thenReturn(Optional.of(new TeamMember(teamId, actorId, TeamRole.VIEWER)));
        assertThatThrownBy(() -> service.removeMember(teamId, actorId, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        verify(members, never()).delete(any());
        verifyNoInteractions(activity);
    }

    @Test
    void finalAdminCannotBeDemotedOrRemoved() {
        TeamMember admin = new TeamMember(teamId, actorId, TeamRole.ADMIN);
        when(members.findForAdministration(actorKey)).thenReturn(Optional.of(admin));
        when(members.findRoleHoldersForAdministration(teamId, TeamRole.ADMIN)).thenReturn(List.of(admin));
        assertThatThrownBy(() -> service.updateMember(teamId, actorId, actorId,
                new UpdateTeamMemberRequest(TeamRole.EDITOR)))
                .isInstanceOf(TeamConflictException.class);
        assertThatThrownBy(() -> service.removeMember(teamId, actorId, actorId))
                .isInstanceOf(TeamConflictException.class);
        assertThat(admin.getRole()).isEqualTo(TeamRole.ADMIN);
        verify(members, never()).delete(any());
        verifyNoInteractions(activity);
    }

    @Test
    void noOpRoleUpdateDoesNotCreateActivity() {
        TeamMember admin = new TeamMember(teamId, actorId, TeamRole.ADMIN);
        when(members.findForAdministration(actorKey)).thenReturn(Optional.of(admin));
        when(users.findById(actorId)).thenReturn(Optional.of(mock(UserAccount.class)));
        assertThat(service.updateMember(teamId, actorId, actorId,
                new UpdateTeamMemberRequest(TeamRole.ADMIN)).role()).isEqualTo(TeamRole.ADMIN);
        verifyNoInteractions(activity);
    }

    @Test
    void requestsNormalizeNamesAndEmailsAndRequireExplicitRoles() {
        assertThat(new CreateTeamRequest(" Team ", " Description ").name()).isEqualTo("Team");
        assertThat(new AddTeamMemberRequest(" ADMIN@scheideckerantunes.com ", TeamRole.EDITOR).email())
                .isEqualTo("admin@scheideckerantunes.com");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new CreateTeamRequest(" ", ""))).isNotEmpty();
            assertThat(validator.validate(new AddTeamMemberRequest("admin@scheideckerantunes.com", null)))
                    .isNotEmpty();
            assertThat(validator.validate(new UpdateTeamMemberRequest(null))).isNotEmpty();
        }
    }
}
