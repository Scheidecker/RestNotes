package com.scheideckerantunes.notes.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues identity-only access tokens; live membership remains in the database.
 *
 * @author Carlos Scheidecker
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtSettings settings;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtSettings settings, Clock clock) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
    }

    public TokenResponse issue(UUID userId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(settings.ttlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(settings.issuer()).audience(List.of(settings.audience()))
                .subject(userId.toString()).issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims)).getTokenValue();
        return new TokenResponse(token, "Bearer", settings.ttlSeconds(), expiresAt);
    }
}
