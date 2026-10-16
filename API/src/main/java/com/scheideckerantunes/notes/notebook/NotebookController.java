package com.scheideckerantunes.notes.notebook;

import com.scheideckerantunes.notes.common.PageResponse;
import com.scheideckerantunes.notes.team.TeamRole;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notebook/permission operations require live authorization and conditional aggregate writes.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1/notebooks")
@SecurityRequirement(name = "bearerAuth")
public class NotebookController {
    private final NotebookService notebooks;

    public NotebookController(NotebookService notebooks) {
        this.notebooks = notebooks;
    }

    @PostMapping
    @Operation(summary = "Create a notebook for a team where the caller is ADMIN")
    public ResponseEntity<NotebookResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateNotebookRequest request) {
        NotebookResponse result = notebooks.create(actor(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/notebooks/" + result.id()))
                .eTag(etag(result, actor(jwt))).body(result);
    }

    @GetMapping
    @Operation(summary = "List owned-team and shared notebooks, ordered by update time and ID")
    public PageResponse<NotebookResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return notebooks.list(actor(jwt), teamId, page, size);
    }

    @GetMapping("/{notebookId}")
    @Operation(summary = "Read notebook metadata and its caller-scoped strong ETag")
    public ResponseEntity<NotebookResponse> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId) {
        NotebookResponse result = notebooks.detail(notebookId, actor(jwt));
        return ResponseEntity.ok().eTag(etag(result, actor(jwt))).body(result);
    }

    @PatchMapping("/{notebookId}")
    @Operation(summary = "Conditionally update notebook metadata; owner-team ADMIN only")
    public ResponseEntity<NotebookResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateNotebookRequest request) {
        NotebookResponse result = notebooks.update(notebookId, actor(jwt), ifMatch, request);
        return ResponseEntity.ok().eTag(etag(result, actor(jwt))).body(result);
    }

    @DeleteMapping("/{notebookId}")
    @Operation(summary = "Conditionally archive a notebook without deleting its notes/history")
    public ResponseEntity<Void> archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long version = notebooks.archive(notebookId, actor(jwt), ifMatch);
        return ResponseEntity.noContent().eTag(adminEtag(notebookId, version, actor(jwt))).build();
    }

    @GetMapping("/{notebookId}/permissions")
    @Operation(summary = "List notebook team grants; owner-team ADMIN only")
    public PageResponse<NotebookPermission> permissions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return notebooks.permissions(notebookId, actor(jwt), page, size);
    }

    @PatchMapping("/{notebookId}/permissions")
    @Operation(summary = "Conditionally grant/change one team's VIEWER or EDITOR access; owner ADMIN only")
    public ResponseEntity<NotebookPermission> share(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notebookId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody NotebookShareRequest request) {
        var result = notebooks.share(notebookId, actor(jwt), ifMatch, request);
        return ResponseEntity.ok().eTag(adminEtag(notebookId, result.version(), actor(jwt))).body(result.permission());
    }

    @DeleteMapping("/{notebookId}/permissions/{teamId}")
    @Operation(summary = "Conditionally revoke a team's grant; owner-team ADMIN only")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID notebookId, @PathVariable UUID teamId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long version = notebooks.revoke(notebookId, actor(jwt), teamId, ifMatch);
        return ResponseEntity.noContent().eTag(adminEtag(notebookId, version, actor(jwt))).build();
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private String etag(NotebookResponse response, UUID actorId) {
        return NotebookEtags.value(response.id(), response.version(), actorId, response.accessLevel());
    }

    private String adminEtag(UUID notebookId, long version, UUID actorId) {
        return NotebookEtags.value(notebookId, version, actorId, TeamRole.ADMIN);
    }
}
