package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.notebook.Notebook;
import com.scheideckerantunes.notes.notebook.NotebookAuthorization;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.notebook.NotebookShareRepository;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Content writes lock sorted access-path teams then the notebook, before any note row.
 *
 * @author Carlos Scheidecker
 */
@Component
public class NoteWriteAccess {
    private final NotebookRepository notebooks;
    private final NotebookShareRepository shares;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final NotebookAuthorization authorization;
    private final EntityManager entityManager;

    public NoteWriteAccess(NotebookRepository notebooks, NotebookShareRepository shares, TeamRepository teams,
            TeamMemberRepository members, NotebookAuthorization authorization, EntityManager entityManager) {
        this.notebooks = notebooks;
        this.shares = shares;
        this.teams = teams;
        this.members = members;
        this.authorization = authorization;
        this.entityManager = entityManager;
    }

    public Access lock(UUID notebookId, UUID actor) {
        var scope = notebooks.findOwnerInfo(notebookId).orElseThrow(ResourceNotFoundException::new);
        if (scope.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        var lockedTeams = new HashSet<UUID>();
        lockedTeams.add(scope.getTeamId());
        shares.findCurrentPaths(notebookId, actor).forEach(path -> lockedTeams.add(path.teamId()));
        lockedTeams.stream().sorted(Comparator.comparing(UUID::toString))
                .forEach(teamId -> teams.findForAdministration(teamId));

        Notebook notebook;
        try {
            notebook = notebooks.findForAdministration(notebookId).orElseThrow(ResourceNotFoundException::new);
            entityManager.refresh(notebook, LockModeType.PESSIMISTIC_WRITE);
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new NoteOperationException(NoteOperationException.Code.ACCESS_CHANGED);
        }
        if (notebook.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        TeamRole role = members.findOwnerRoles(actor, java.util.List.of(notebook.getTeamId()))
                .stream().map(TeamMemberRepository.OwnerRole::getRole).findFirst().orElse(null);
        for (var path : shares.findCurrentPaths(notebookId, actor)) {
            if (lockedTeams.contains(path.teamId()) && role != TeamRole.ADMIN) {
                TeamRole capped = path.effectiveRole();
                if (role == null || capped == TeamRole.EDITOR) {
                    role = capped;
                }
            }
        }
        if (role == TeamRole.ADMIN || role == TeamRole.EDITOR) {
            return new Access(notebook, role);
        }
        TeamRole current = authorization.requireRead(notebook, actor);
        if (current != TeamRole.VIEWER) {
            // A new path appeared after lock selection: never authorize using an unlocked team.
            throw new NoteOperationException(NoteOperationException.Code.ACCESS_CHANGED);
        }
        throw new AccessDeniedException("Note writes require current EDITOR or ADMIN access");
    }

    /**
     * Valid only inside the caller's write transaction while its locks remain held.
     *
     * @author Carlos Scheidecker
     */
    public record Access(Notebook notebook, TeamRole role) {
    }
}
