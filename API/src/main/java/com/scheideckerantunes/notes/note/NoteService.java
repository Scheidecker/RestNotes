package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.activity.ActivityLog;
import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.common.PageResponse;
import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.history.NoteHistory;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.notebook.Notebook;
import com.scheideckerantunes.notes.notebook.NotebookAuthorization;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Note content, history and activity commit together; all content writes use live locked capabilities.
 *
 * @author Carlos Scheidecker
 */
@Service
@Transactional(readOnly = true)
public class NoteService {
    private final NoteRepository notes;
    private final NotebookRepository notebooks;
    private final NotebookAuthorization authorization;
    private final NoteWriteAccess writeAccess;
    private final NoteTagRepository tags;
    private final TagRepository tagCatalog;
    private final NoteHistoryRepository history;
    private final ActivityLogRepository activity;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final Clock clock;

    public NoteService(NoteRepository notes, NotebookRepository notebooks, NotebookAuthorization authorization,
            NoteWriteAccess writeAccess, NoteTagRepository tags, NoteHistoryRepository history,
            ActivityLogRepository activity, EntityManager entityManager, ObjectMapper json, Clock clock,
            TagRepository tagCatalog) {
        this.notes = notes;
        this.notebooks = notebooks;
        this.authorization = authorization;
        this.writeAccess = writeAccess;
        this.tags = tags;
        this.history = history;
        this.activity = activity;
        this.entityManager = entityManager;
        this.json = json;
        this.clock = clock;
        this.tagCatalog = tagCatalog;
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NoteResponse create(UUID notebookId, UUID actor, CreateNoteRequest request) {
        var access = writeAccess.lock(notebookId, actor);
        requireContentBound(request.content());
        Note note = notes.saveAndFlush(new Note(notebookId, access.notebook().getTeamId(),
                request.title(), request.content(), request.pinned(), actor));
        snapshot(note, actor);
        record(note, actor, "NOTE_CREATED");
        return response(note, access);
    }

    public PageResponse<NoteSummary> list(UUID notebookId, UUID actor, int page, int size) {
        notebooks.findVisibleById(notebookId, actor).orElseThrow(ResourceNotFoundException::new);
        return PageResponse.from(notes.findSummaries(notebookId, actor, PageRequest.of(page, size)));
    }

    public NoteResponse detail(UUID noteId, UUID actor) {
        Note note = notes.findVisibleById(noteId, actor).orElseThrow(ResourceNotFoundException::new);
        Notebook book = notebooks.findVisibleById(note.getNotebookId(), actor)
                .orElseThrow(ResourceNotFoundException::new);
        return NoteResponse.from(note, book.getVersion(), authorization.requireRead(book, actor), tagNames(noteId));
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NoteResponse replace(UUID noteId, UUID actor, String ifMatch, ReplaceNoteRequest request) {
        var locked = writable(noteId, actor, ifMatch, false);
        requireContentBound(request.content());
        change(locked.note(), actor, request.title(), request.content(), request.pinned());
        return response(locked.note(), locked.access());
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NoteResponse patch(UUID noteId, UUID actor, String ifMatch, PatchNoteRequest request) {
        var locked = writable(noteId, actor, ifMatch, false);
        if (!request.titlePresent() && !request.contentPresent() && !request.pinnedPresent()) {
            throw new NoteOperationException(NoteOperationException.Code.EMPTY_PATCH);
        }
        if ((request.titlePresent() && request.getTitle() == null)
                || (request.contentPresent() && request.getContent() == null)
                || (request.pinnedPresent() && request.getPinned() == null)) {
            throw new NoteOperationException(NoteOperationException.Code.NULL_PATCH_FIELD);
        }
        Note note = locked.note();
        String content = request.contentPresent() ? request.getContent() : note.getContent();
        requireContentBound(content);
        change(note, actor, request.titlePresent() ? request.getTitle() : note.getTitle(), content,
                request.pinnedPresent() ? request.getPinned() : note.isPinned());
        return response(note, locked.access());
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public String archive(UUID noteId, UUID actor, String ifMatch) {
        var locked = writable(noteId, actor, ifMatch, true);
        if (locked.note().archive(clock.instant(), actor)) {
            notes.flush();
            record(locked.note(), actor, "NOTE_ARCHIVED");
        }
        return etag(locked.note(), locked.access(), actor);
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public NoteResponse addTag(UUID noteId, UUID actor, String ifMatch, AddTagRequest request) {
        var locked = writable(noteId, actor, ifMatch, false);
        Note note = locked.note();
        String name = TagNames.require(request.name());
        var existing = tagCatalog.findByTeamIdAndNormalizedName(note.getTeamId(), name);
        if (existing.isPresent() && tags.existsById(new NoteTagId(noteId, existing.get().getId()))) {
            return response(note, locked.access());
        }
        if (tagNames(noteId).size() >= NoteLimits.TAGS) {
            throw new NoteOperationException(NoteOperationException.Code.TAG_LIMIT);
        }
        Tag tag = existing.orElseGet(() -> tagCatalog.saveAndFlush(new Tag(note.getTeamId(), name)));
        tags.saveAndFlush(new NoteTag(noteId, tag.getId(), note.getTeamId()));
        tagChanged(note, actor, "NOTE_TAGGED");
        return response(note, locked.access());
    }

    @Transactional(timeout = 15, isolation = Isolation.READ_COMMITTED)
    public String removeTag(UUID noteId, UUID actor, String name, String ifMatch) {
        var locked = writable(noteId, actor, ifMatch, false);
        Note note = locked.note();
        var tag = tagCatalog.findByTeamIdAndNormalizedName(note.getTeamId(), TagNames.require(name));
        if (tag.isPresent()) {
            var association = tags.findById(new NoteTagId(noteId, tag.get().getId()));
            if (association.isPresent()) {
                tags.delete(association.get());
                tags.flush();
                tagChanged(note, actor, "NOTE_UNTAGGED");
            }
        }
        return etag(note, locked.access(), actor);
    }

    private void tagChanged(Note note, UUID actor, String action) {
        note.tagsChanged(actor);
        notes.flush();
        snapshot(note, actor);
        record(note, actor, action);
    }

    private LockedNote writable(UUID noteId, UUID actor, String ifMatch, boolean allowArchived) {
        var scope = notes.findScope(noteId).orElseThrow(ResourceNotFoundException::new);
        if (!allowArchived && scope.getArchivedAt() != null) {
            throw new ResourceNotFoundException();
        }
        var access = writeAccess.lock(scope.getNotebookId(), actor);
        Note note;
        try {
            note = notes.findForModification(noteId).orElseThrow(ResourceNotFoundException::new);
            entityManager.refresh(note, LockModeType.PESSIMISTIC_WRITE);
        } catch (OptimisticLockingFailureException | OptimisticLockException exception) {
            throw new NoteOperationException(NoteOperationException.Code.STALE_IF_MATCH);
        }
        if (!note.getNotebookId().equals(access.notebook().getId())
                || (!allowArchived && note.getArchivedAt() != null)) {
            throw new ResourceNotFoundException();
        }
        NoteEtags.require(etag(note, access, actor), ifMatch);
        return new LockedNote(note, access);
    }

    private void change(Note note, UUID actor, String title, String content, boolean pinned) {
        if (note.applyChanges(title, content, pinned, actor)) {
            notes.flush();
            snapshot(note, actor);
            record(note, actor, "NOTE_UPDATED");
        }
    }

    private void snapshot(Note note, UUID actor) {
        int version = Math.incrementExact(history.findLatestVersion(note.getId()));
        history.saveAndFlush(new NoteHistory(note.getId(), version, note.getTitle(), note.getContent(),
                note.isPinned(), json.writeValueAsString(tagNames(note.getId())), actor));
    }

    private List<String> tagNames(UUID noteId) {
        List<String> names = tags.findNames(noteId, PageRequest.of(0, NoteLimits.TAGS + 1));
        if (names.size() > NoteLimits.TAGS) {
            throw new NoteOperationException(NoteOperationException.Code.TAG_LIMIT);
        }
        return names;
    }

    private NoteResponse response(Note note, NoteWriteAccess.Access access) {
        return NoteResponse.from(note, access.notebook().getVersion(), access.role(), tagNames(note.getId()));
    }

    private String etag(Note note, NoteWriteAccess.Access access, UUID actor) {
        return NoteEtags.value(note.getId(), note.getVersion(), note.getNotebookId(),
                access.notebook().getVersion(), actor, access.role());
    }

    private void requireContentBound(String content) {
        if (!NoteLimits.contentFits(content)) {
            throw new NoteOperationException(NoteOperationException.Code.CONTENT_TOO_LARGE);
        }
    }

    private void record(Note note, UUID actor, String action) {
        activity.save(new ActivityLog(note.getTeamId(), actor, action, "note", note.getId()));
    }

    /**
     * Internal transaction-scoped state is never serialized.
     *
     * @author Carlos Scheidecker
     */
    private record LockedNote(Note note, NoteWriteAccess.Access access) {
    }
}
