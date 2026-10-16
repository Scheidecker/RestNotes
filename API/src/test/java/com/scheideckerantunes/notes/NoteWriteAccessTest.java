package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scheideckerantunes.notes.note.NoteOperationException;
import com.scheideckerantunes.notes.note.NoteWriteAccess;
import com.scheideckerantunes.notes.notebook.Notebook;
import com.scheideckerantunes.notes.notebook.NotebookAccessPath;
import com.scheideckerantunes.notes.notebook.NotebookAuthorization;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.notebook.NotebookShareRepository;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.team.Team;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Newly discovered grants must not authorize writes without their membership team lock.
 *
 * @author Carlos Scheidecker
 */
class NoteWriteAccessTest {
    private final UUID bookId = UUID.randomUUID();
    private final UUID ownerTeam = UUID.randomUUID();
    private final UUID recipientTeam = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final NotebookRepository notebooks = mock(NotebookRepository.class);
    private final NotebookShareRepository shares = mock(NotebookShareRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final NotebookAuthorization policy = mock(NotebookAuthorization.class);
    private final NoteWriteAccess access = new NoteWriteAccess(
            notebooks, shares, teams, members, policy, mock(EntityManager.class));

    @BeforeEach
    void configure() {
        var scope = mock(NotebookRepository.OwnerInfo.class);
        when(scope.getTeamId()).thenReturn(ownerTeam);
        when(notebooks.findOwnerInfo(bookId)).thenReturn(Optional.of(scope));
        Notebook book = mock(Notebook.class);
        when(book.getId()).thenReturn(bookId);
        when(book.getTeamId()).thenReturn(ownerTeam);
        when(notebooks.findForAdministration(bookId)).thenReturn(Optional.of(book));
        when(members.findOwnerRoles(eq(actor), any())).thenReturn(List.of());
        when(teams.findForAdministration(any())).thenReturn(Optional.of(mock(Team.class)));
        when(policy.requireRead(book, actor)).thenReturn(TeamRole.VIEWER);
    }

    @Test
    void existingEditorPathLocksItsTeamAndPermitsWriting() {
        var path = new NotebookAccessPath(recipientTeam, ShareAccessLevel.EDITOR, TeamRole.ADMIN);
        when(shares.findCurrentPaths(bookId, actor)).thenReturn(List.of(path));
        assertThat(access.lock(bookId, actor).role()).isEqualTo(TeamRole.EDITOR);
        verify(teams).findForAdministration(recipientTeam);
    }

    @Test
    void viewerMembershipCannotUseAnEditorGrantToWrite() {
        when(shares.findCurrentPaths(bookId, actor)).thenReturn(List.of(
                new NotebookAccessPath(recipientTeam, ShareAccessLevel.EDITOR, TeamRole.VIEWER)));
        assertThatThrownBy(() -> access.lock(bookId, actor)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void newUnlockedEditorPathRequiresRetryRatherThanGrantingPermission() {
        when(shares.findCurrentPaths(bookId, actor)).thenReturn(List.of(), List.of(
                new NotebookAccessPath(recipientTeam, ShareAccessLevel.EDITOR, TeamRole.ADMIN)));
        when(policy.requireRead(any(), eq(actor))).thenReturn(TeamRole.EDITOR);
        assertThatThrownBy(() -> access.lock(bookId, actor))
                .isInstanceOf(NoteOperationException.class).hasMessage("ACCESS_CHANGED");
        verify(teams, never()).findForAdministration(recipientTeam);
    }
}
