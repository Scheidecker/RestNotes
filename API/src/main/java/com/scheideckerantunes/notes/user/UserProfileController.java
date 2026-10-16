package com.scheideckerantunes.notes.user;

import com.scheideckerantunes.notes.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scalar profile and a separately paginated membership collection.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1/users/me")
@SecurityRequirement(name = "bearerAuth")
public class UserProfileController {
    private final UserProfileService profiles;

    public UserProfileController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "Read the authenticated user's profile")
    public UserProfile profile(@AuthenticationPrincipal Jwt jwt) {
        return profiles.profile(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/teams")
    @Operation(summary = "List current team affiliations, ordered by name and ID")
    public PageResponse<TeamAffiliation> teams(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return profiles.teams(UUID.fromString(jwt.getSubject()), page, size);
    }
}
