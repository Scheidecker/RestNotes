package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.activity.ActivityLog;
import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.common.PageResponse;
import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * Owner-team then notebook locking serializes writes; READ_COMMITTED keeps post-lock refreshes current.
 *
 * @author Carlos Scheidecker
 */
@Service
@Transactional(readOnly = true)
public class NotebookService {
    private final NotebookRepository notebooks;
    private final NotebookShareRepository shares;
    private final NotebookAuthorization authorization;
    private final TeamRepository teams;
    private final ActivityLogRepository activity;
    private final EntityManager entityManager;
    private final Clock clock;

    public NotebookService(NotebookRepository notebooks, NotebookShareRepository shares,
            NotebookAuthorization authorization, TeamRepository teams, ActivityLogRepository activity,
            EntityManager entityManager, Clock clock) {
        this.notebooks = notebooks;
        this.shares = shares;
        this.authorization = authorization;
        this.teams = teams;
        this.activity = activity;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NotebookResponse create(UUID actorId, CreateNotebookRequest request) {
        authorization.lockOwnerAdmin(request.teamId(), actorId, null);
        Notebook notebook = notebooks.saveAndFlush(
                new Notebook(request.teamId(), request.title(), request.description(), actorId));
        record(notebook, actorId, "NOTEBOOK_CREATED");
        return NotebookResponse.from(notebook, TeamRole.ADMIN);
    }

    public PageResponse<NotebookResponse> list(UUID actorId, UUID teamId, int page, int size) {
        var result = notebooks.findVisibleTo(actorId, teamId, PageRequest.of(page, size));
        var roles = authorization.rolesFor(result.getContent(), actorId);
        return PageResponse.from(result.map(book -> NotebookResponse.from(book, roles.get(book.getId()))));
    }

    public NotebookResponse detail(UUID notebookId, UUID actorId) {
        Notebook notebook = readable(notebookId, actorId);
        return NotebookResponse.from(notebook, authorization.requireRead(notebook, actorId));
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NotebookResponse update(UUID notebookId, UUID actorId, String ifMatch, UpdateNotebookRequest request) {
        Notebook notebook = writable(notebookId, actorId, ifMatch, false, null);
        if (request.title() == null && request.description() == null) {
            throw new NotebookOperationException(NotebookOperationException.Code.EMPTY_PATCH);
        }
        if (notebook.updateMetadata(request.title(), request.description())) {
            notebooks.flush();
            record(notebook, actorId, "NOTEBOOK_UPDATED");
        }
        return NotebookResponse.from(notebook, TeamRole.ADMIN);
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public long archive(UUID notebookId, UUID actorId, String ifMatch) {
        Notebook notebook = writable(notebookId, actorId, ifMatch, true, null);
        if (notebook.archive(clock.instant())) {
            notebooks.flush();
            record(notebook, actorId, "NOTEBOOK_ARCHIVED");
        }
        return notebook.getVersion();
    }

    public PageResponse<NotebookPermission> permissions(UUID notebookId, UUID actorId, int page, int size) {
        Notebook notebook = readable(notebookId, actorId);
        authorization.requireOwnerAdminRead(notebook, actorId);
        return PageResponse.from(shares.findPermissions(notebookId, PageRequest.of(page, size)));
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public PermissionChange share(UUID notebookId, UUID actorId, String ifMatch, NotebookShareRequest request) {
        Notebook notebook = writable(notebookId, actorId, ifMatch, false, request.teamId());
        if (notebook.getTeamId().equals(request.teamId())) {
            throw new NotebookOperationException(NotebookOperationException.Code.OWNER_TEAM_SHARE);
        }
        var target = teams.findById(request.teamId()).orElseThrow(ResourceNotFoundException::new);
        Optional<NotebookShare> current = currentShare(new NotebookShareId(notebookId, request.teamId()));
        NotebookShare share;
        if (current.isEmpty()) {
            share = shares.saveAndFlush(new NotebookShare(notebookId, request.teamId(), request.accessLevel()));
            permissionsChanged(notebook, actorId, "NOTEBOOK_SHARED");
        } else {
            share = current.get();
            if (share.getAccessLevel() != request.accessLevel()) {
                share.changeAccess(request.accessLevel());
                shares.flush();
                permissionsChanged(notebook, actorId, "NOTEBOOK_SHARE_UPDATED");
            }
        }
        return new PermissionChange(new NotebookPermission(target.getId(), target.getName(), share.getAccessLevel(),
                share.getSharedAt(), share.getUpdatedAt()), notebook.getVersion());
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public long revoke(UUID notebookId, UUID actorId, UUID recipientTeamId, String ifMatch) {
        Notebook notebook = writable(notebookId, actorId, ifMatch, false, recipientTeamId);
        Optional<NotebookShare> share = currentShare(new NotebookShareId(notebookId, recipientTeamId));
        if (share.isPresent()) {
            shares.delete(share.get());
            shares.flush();
            permissionsChanged(notebook, actorId, "NOTEBOOK_UNSHARED");
        }
        return notebook.getVersion();
    }

    private Notebook readable(UUID notebookId, UUID actorId) {
        return notebooks.findVisibleById(notebookId, actorId).orElseThrow(ResourceNotFoundException::new);
    }

    private Notebook writable(UUID notebookId, UUID actorId, String ifMatch,
            boolean allowArchived, UUID recipientTeamId) {
        var initial = notebooks.findOwnerInfo(notebookId).orElseThrow(ResourceNotFoundException::new);
        if (!allowArchived && initial.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        if (recipientTeamId != null) {
            // FK inserts/deletes may lock the recipient; reciprocal shares must use the same team order.
            Stream.of(initial.getTeamId(), recipientTeamId).distinct()
                    .sorted(Comparator.comparing(UUID::toString))
                    .forEach(teamId -> teams.findForAdministration(teamId));
            // Missing recipients are deliberately reported only after owner authorization below.
        }
        authorization.lockOwnerAdmin(initial.getTeamId(), actorId, notebookId);
        Notebook current;
        try {
            current = notebooks.findForAdministration(notebookId).orElseThrow(ResourceNotFoundException::new);
            entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new NotebookOperationException(NotebookOperationException.Code.STALE_IF_MATCH);
        }
        if (!allowArchived && current.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        NotebookEtags.require(NotebookEtags.value(notebookId, current.getVersion(), actorId, TeamRole.ADMIN), ifMatch);
        return current;
    }

    private Optional<NotebookShare> currentShare(NotebookShareId id) {
        return shares.findForAdministration(id).map(share -> {
            entityManager.refresh(share, LockModeType.PESSIMISTIC_WRITE);
            return share;
        });
    }

    private void permissionsChanged(Notebook notebook, UUID actorId, String action) {
        notebook.permissionsChanged();
        notebooks.flush();
        record(notebook, actorId, action);
    }

    private void record(Notebook notebook, UUID actorId, String action) {
        activity.save(new ActivityLog(notebook.getTeamId(), actorId, action, "notebook", notebook.getId()));
    }

    /**
     * Internal service result keeps controller serialization free of JPA entities.
     *
     * @author Carlos Scheidecker
     */
    public record PermissionChange(NotebookPermission permission, long version) {
    }
}
