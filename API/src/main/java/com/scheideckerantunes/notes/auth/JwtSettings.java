package com.scheideckerantunes.notes.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Non-secret token policy and locations of externally supplied PEM key files.
 *
 * @author Carlos Scheidecker
 */
@Validated
@ConfigurationProperties("restnotes.security.jwt")
public record JwtSettings(
        @NotBlank String issuer,
        @NotBlank String audience,
        @Min(60) @Max(3600) long ttlSeconds,
        String privateKeyLocation,
        String publicKeyLocation) {
}
