package com.scheideckerantunes.notes.auth;

import com.scheideckerantunes.notes.user.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local identity onboarding and short-lived bearer token issuance.
 *
 * @author Carlos Scheidecker
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final AuthenticationService authentication;

    public AuthenticationController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/register")
    @Operation(summary = "Register an account without team memberships")
    public ResponseEntity<UserProfile> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.created(URI.create("/api/v1/users/me"))
                .cacheControl(CacheControl.noStore()).body(authentication.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate using email/password and issue an RS256 access token")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(authentication.login(request));
    }
}
