package com.scheideckerantunes.notes.note;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lock credentials are never returned by reader status or ordinary note routes.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1/notes/{noteId}/lock")
@SecurityRequirement(name = "bearerAuth")
public class NoteLockController {
    private final NoteLockService locks;

    public NoteLockController(NoteLockService locks) {
        this.locks = locks;
    }

    @GetMapping
    @Operation(summary = "Read current lease metadata, never its token; requires note read access")
    public ResponseEntity<NoteLockStatus> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(locks.status(noteId, UUID.fromString(jwt.getSubject())));
    }

    @PostMapping
    @Operation(summary = "Acquire a five-minute editing lease or renew using its token; requires note If-Match")
    public ResponseEntity<NoteLockResponse> acquire(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = NoteLockTokens.HEADER, required = false) String token) {
        var result = locks.acquire(noteId, UUID.fromString(jwt.getSubject()), ifMatch, token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(result.etag()).body(result.response());
    }

    @DeleteMapping
    @Operation(summary = "Release an editing lease using its owner token and current note If-Match")
    public ResponseEntity<Void> release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID noteId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = NoteLockTokens.HEADER, required = false) String token) {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .eTag(locks.release(noteId, UUID.fromString(jwt.getSubject()), ifMatch, token)).build();
    }
}
