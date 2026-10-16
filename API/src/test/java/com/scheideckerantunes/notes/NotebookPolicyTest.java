package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.notebook.Notebook;
import com.scheideckerantunes.notes.notebook.NotebookAuthorization;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookGrant;
import com.scheideckerantunes.notes.notebook.NotebookOperationException;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.notebook.NotebookShareRepository;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.team.Team;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Sharing is bounded by live membership and never becomes owner administration.
 *
 * @author Carlos Scheidecker
 */
class NotebookPolicyTest {
    private final UUID bookId = UUID.randomUUID();
    private final UUID teamId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final NotebookShareRepository shares = mock(NotebookShareRepository.class);
    private final NotebookRepository notebooks = mock(NotebookRepository.class);
    private final NotebookAuthorization policy = new NotebookAuthorization(
            teams, members, shares, mock(EntityManager.class), notebooks);

    @Test
    void allRecipientRoleCombinationsAreCapped() {
        for (ShareAccessLevel grant : ShareAccessLevel.values()) {
            for (TeamRole role : TeamRole.values()) {
                TeamRole expected = grant == ShareAccessLevel.EDITOR && role != TeamRole.VIEWER
                        ? TeamRole.EDITOR : TeamRole.VIEWER;
                assertThat(grant.cappedBy(role)).isEqualTo(expected);
            }
        }
    }

    @Test
    void strongestLegitimatePathWinsWithoutExternalAdmin() {
        Notebook book = book();
        when(members.findOwnerRoles(eq(actorId), any())).thenReturn(List.of());
        when(shares.findCurrentGrants(any(), eq(actorId))).thenReturn(List.of(
                new NotebookGrant(bookId, ShareAccessLevel.VIEWER, TeamRole.ADMIN),
                new NotebookGrant(bookId, ShareAccessLevel.EDITOR, TeamRole.EDITOR)));
        assertThat(policy.requireRead(book, actorId)).isEqualTo(TeamRole.EDITOR);
        assertThatThrownBy(() -> policy.requireOwnerAdminRead(book, actorId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void owningAdminCannotBeReducedByAWeakerExternalGrant() {
        Notebook book = book();
        TeamMemberRepository.OwnerRole owner = mock(TeamMemberRepository.OwnerRole.class);
        when(owner.getTeamId()).thenReturn(teamId);
        when(owner.getRole()).thenReturn(TeamRole.ADMIN);
        when(members.findOwnerRoles(eq(actorId), any())).thenReturn(List.of(owner));
        when(shares.findCurrentGrants(any(), eq(actorId))).thenReturn(List.of(
                new NotebookGrant(bookId, ShareAccessLevel.VIEWER, TeamRole.VIEWER)));
        assertThat(policy.requireRead(book, actorId)).isEqualTo(TeamRole.ADMIN);
    }

    @Test
    void archivedBooksDoNotResolveAnyAccessPaths() {
        Notebook book = book();
        when(book.getArchivedAt()).thenReturn(Instant.now());
        assertThat(policy.rolesFor(List.of(book), actorId)).isEmpty();
        verifyNoInteractions(members, shares);
    }

    @Test
    void missingPathsAreHiddenAndExternalEditorsCannotAdminister() {
        Notebook book = book();
        when(members.findOwnerRoles(eq(actorId), any())).thenReturn(List.of());
        when(shares.findCurrentGrants(any(), eq(actorId))).thenReturn(List.of());
        assertThatThrownBy(() -> policy.requireRead(book, actorId)).isInstanceOf(ResourceNotFoundException.class);
        when(teams.findForAdministration(teamId)).thenReturn(Optional.of(mock(Team.class)));
        when(members.findForAdministration(any())).thenReturn(Optional.empty());
        when(shares.findCurrentGrants(any(), eq(actorId))).thenReturn(List.of(
                new NotebookGrant(bookId, ShareAccessLevel.EDITOR, TeamRole.ADMIN)));
        when(notebooks.findVisibleById(bookId, actorId)).thenReturn(Optional.of(book));
        assertThatThrownBy(() -> policy.lockOwnerAdmin(teamId, actorId, bookId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void etagsScopeVersionsToTheActorAndEffectiveRole() {
        String expected = NotebookEtags.value(bookId, 3, actorId, TeamRole.ADMIN);
        NotebookEtags.require(expected, expected);
        rejected(expected, null, "MISSING_IF_MATCH");
        rejected(expected, "*", "INVALID_IF_MATCH");
        rejected(expected, "W/" + expected, "INVALID_IF_MATCH");
        rejected(expected, expected + "," + expected, "INVALID_IF_MATCH");
        rejected(expected, NotebookEtags.value(bookId, 2, actorId, TeamRole.ADMIN), "STALE_IF_MATCH");
        rejected(expected, NotebookEtags.value(bookId, 3, UUID.randomUUID(), TeamRole.ADMIN), "STALE_IF_MATCH");
        rejected(expected, NotebookEtags.value(bookId, 3, actorId, TeamRole.VIEWER), "STALE_IF_MATCH");
    }

    @Test
    void metadataPatchAndArchiveHaveExplicitNoOpSemantics() {
        Notebook book = new Notebook(teamId, "Title", "Description", actorId);
        assertThat(book.updateMetadata(null, null)).isFalse();
        assertThat(book.updateMetadata("Title", "Description")).isFalse();
        assertThat(book.updateMetadata(null, "")).isTrue();
        assertThat(book.getDescription()).isEmpty();
        assertThat(book.archive(Instant.now())).isTrue();
        assertThat(book.archive(Instant.now())).isFalse();
    }

    private Notebook book() {
        Notebook book = mock(Notebook.class);
        when(book.getId()).thenReturn(bookId);
        when(book.getTeamId()).thenReturn(teamId);
        return book;
    }

    private void rejected(String expected, String supplied, String code) {
        assertThatThrownBy(() -> NotebookEtags.require(expected, supplied))
                .isInstanceOf(NotebookOperationException.class).hasMessage(code);
    }
}
