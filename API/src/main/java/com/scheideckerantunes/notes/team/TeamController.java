package com.scheideckerantunes.notes.team;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Team endpoints derive the acting user exclusively from the validated bearer token.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1/teams")
@SecurityRequirement(name = "bearerAuth")
public class TeamController {
    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    @PostMapping
    @Operation(summary = "Create a team and atomically become its ADMIN")
    public ResponseEntity<TeamResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTeamRequest request) {
        TeamResponse result = teams.create(actor(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/teams/" + result.id())).body(result);
    }

    @GetMapping
    @Operation(summary = "List the caller's teams, ordered by name and ID")
    public PageResponse<TeamResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return teams.list(actor(jwt), page, size);
    }

    @GetMapping("/{teamId}")
    @Operation(summary = "Read a team visible through current membership")
    public TeamResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId) {
        return teams.detail(teamId, actor(jwt));
    }

    @PostMapping("/{teamId}/members")
    @Operation(summary = "Add an existing registered user; requires team ADMIN")
    public ResponseEntity<TeamMemberResponse> addMember(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId, @Valid @RequestBody AddTeamMemberRequest request) {
        TeamMemberResponse result = teams.addMember(teamId, actor(jwt), request);
        return ResponseEntity.created(URI.create("/api/v1/teams/" + teamId + "/members/" + result.userId()))
                .body(result);
    }

    @GetMapping("/{teamId}/members")
    @Operation(summary = "List members and roles, ordered by full name and user ID")
    public PageResponse<TeamMemberResponse> members(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID teamId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return teams.listMembers(teamId, actor(jwt), page, size);
    }

    @PatchMapping("/{teamId}/members/{userId}")
    @Operation(summary = "Change a member's role; cannot demote the final ADMIN")
    public TeamMemberResponse updateMember(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId, @PathVariable UUID userId,
            @Valid @RequestBody UpdateTeamMemberRequest request) {
        return teams.updateMember(teamId, actor(jwt), userId, request);
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @Operation(summary = "Remove a member; cannot remove the final ADMIN")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID teamId, @PathVariable UUID userId) {
        teams.removeMember(teamId, actor(jwt), userId);
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
