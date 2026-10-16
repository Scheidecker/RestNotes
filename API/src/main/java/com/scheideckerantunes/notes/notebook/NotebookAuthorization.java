package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Resolves access in batches and never promotes external grants to owner administration.
 *
 * @author Carlos Scheidecker
 */
@Component
public class NotebookAuthorization {
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final NotebookShareRepository shares;
    private final NotebookRepository notebooks;
    private final EntityManager entityManager;

    public NotebookAuthorization(TeamRepository teams, TeamMemberRepository members,
            NotebookShareRepository shares, EntityManager entityManager, NotebookRepository notebooks) {
        this.teams = teams;
        this.members = members;
        this.shares = shares;
        this.entityManager = entityManager;
        this.notebooks = notebooks;
    }

    public Map<UUID, TeamRole> rolesFor(List<Notebook> notebooks, UUID userId) {
        List<Notebook> active = notebooks.stream().filter(book -> book.getArchivedAt() == null).toList();
        if (active.isEmpty()) {
            return Map.of();
        }
        Map<UUID, TeamRole> ownerRoles = new HashMap<>();
        members.findOwnerRoles(userId, active.stream().map(Notebook::getTeamId).distinct().toList())
                .forEach(member -> ownerRoles.put(member.getTeamId(), member.getRole()));
        Map<UUID, TeamRole> result = new HashMap<>();
        for (Notebook notebook : active) {
            if (notebook.getArchivedAt() == null && ownerRoles.containsKey(notebook.getTeamId())) {
                result.put(notebook.getId(), ownerRoles.get(notebook.getTeamId()));
            }
        }
        shares.findCurrentGrants(active.stream().map(Notebook::getId).toList(), userId)
                .forEach(grant -> result.merge(grant.notebookId(),
                        grant.accessLevel().cappedBy(grant.membershipRole()), NotebookAuthorization::stronger));
        return result;
    }

    public TeamRole requireRead(Notebook notebook, UUID userId) {
        TeamRole role = rolesFor(List.of(notebook), userId).get(notebook.getId());
        if (role == null) {
            throw new ResourceNotFoundException();
        }
        return role;
    }

    public void requireOwnerAdminRead(Notebook notebook, UUID userId) {
        if (requireRead(notebook, userId) != TeamRole.ADMIN) {
            throw new AccessDeniedException("Only owner-team ADMINs may manage notebook permissions");
        }
    }

    public void lockOwnerAdmin(UUID ownerTeamId, UUID userId, UUID notebookId) {
        teams.findForAdministration(ownerTeamId).orElseThrow(ResourceNotFoundException::new);
        var membership = members.findForAdministration(new TeamMemberId(ownerTeamId, userId));
        if (membership.isPresent()) {
            entityManager.refresh(membership.get(), LockModeType.PESSIMISTIC_WRITE);
            if (membership.get().getRole() == TeamRole.ADMIN) {
                return;
            }
            throw new AccessDeniedException("Notebook administration requires owner-team ADMIN");
        }
        if (notebookId != null) {
            notebooks.findVisibleById(notebookId, userId).orElseThrow(ResourceNotFoundException::new);
            throw new AccessDeniedException("Shared access does not grant notebook administration");
        }
        throw new ResourceNotFoundException();
    }

    private static TeamRole stronger(TeamRole first, TeamRole second) {
        if (first == TeamRole.ADMIN || second == TeamRole.ADMIN) {
            return TeamRole.ADMIN;
        }
        return first == TeamRole.EDITOR || second == TeamRole.EDITOR ? TeamRole.EDITOR : TeamRole.VIEWER;
    }
}
