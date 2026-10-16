package com.scheideckerantunes.notes.note;

import com.scheideckerantunes.notes.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Note CRUD derives all attribution and capabilities from validated identity/current membership.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
public class NoteController {
    private final NoteService notes;

    public NoteController(NoteService notes) {
        this.notes = notes;
    }

    @PostMapping("/notebooks/{notebookId}/notes")
    @Operation(summary = "Create a Markdown note; requires current EDITOR or ADMIN capability")
    public ResponseEntity<NoteResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @Valid @RequestBody CreateNoteRequest request) {
        NoteResponse result = notes.create(notebookId, actor(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/notes/" + result.id()))
                .eTag(NoteEtags.from(result, actor(jwt))).body(result);
    }

    @GetMapping("/notebooks/{notebookId}/notes")
    @Operation(summary = "List visible note summaries, pinned first then update time and ID")
    public PageResponse<NoteSummary> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return notes.list(notebookId, actor(jwt), page, size);
    }

    @GetMapping("/notes/{noteId}")
    @Operation(summary = "Read a note and its caller/notebook-scoped strong ETag")
    public ResponseEntity<NoteResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId) {
        NoteResponse result = notes.detail(noteId, actor(jwt));
        return ResponseEntity.ok().eTag(NoteEtags.from(result, actor(jwt))).body(result);
    }

    @PutMapping("/notes/{noteId}")
    @Operation(summary = "Replace title/content/pinned; requires a current note If-Match")
    public ResponseEntity<NoteResponse> replace(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReplaceNoteRequest request) {
        NoteResponse result = notes.replace(noteId, actor(jwt), ifMatch, request);
        return ResponseEntity.ok().eTag(NoteEtags.from(result, actor(jwt))).body(result);
    }

    @PatchMapping("/notes/{noteId}")
    @Operation(summary = "Partially update title/content/pinned; explicit null is not allowed")
    public ResponseEntity<NoteResponse> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody PatchNoteRequest request) {
        NoteResponse result = notes.patch(noteId, actor(jwt), ifMatch, request);
        return ResponseEntity.ok().eTag(NoteEtags.from(result, actor(jwt))).body(result);
    }

    @DeleteMapping("/notes/{noteId}")
    @Operation(summary = "Conditionally archive a note, preserving its content and history")
    public ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ResponseEntity.noContent().eTag(notes.archive(noteId, actor(jwt), ifMatch)).build();
    }

    @PostMapping("/notes/{noteId}/tags")
    @Operation(summary = "Add/reuse one team-scoped tag; current note If-Match and EDITOR access required")
    public ResponseEntity<NoteResponse> addTag(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody AddTagRequest request) {
        NoteResponse result = notes.addTag(noteId, actor(jwt), ifMatch, request);
        return ResponseEntity.ok().eTag(NoteEtags.from(result, actor(jwt))).body(result);
    }

    @DeleteMapping("/notes/{noteId}/tags/{tagName}")
    @Operation(summary = "Remove a tag association without deleting the reusable team catalog entry")
    public ResponseEntity<Void> removeTag(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @PathVariable String tagName, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ResponseEntity.noContent().eTag(notes.removeTag(noteId, actor(jwt), tagName, ifMatch)).build();
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
