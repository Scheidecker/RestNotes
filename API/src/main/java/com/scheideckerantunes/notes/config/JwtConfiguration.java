package com.scheideckerantunes.notes.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.scheideckerantunes.notes.auth.JwtKeyLoader;
import com.scheideckerantunes.notes.auth.JwtSettings;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Uses Spring Security's encoder/decoder, with an explicit RS256-only trust policy.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtSettings.class)
public class JwtConfiguration {

    @Bean
    Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtKeyLoader.Keys jwtKeys(JwtSettings settings, Environment environment, ResourceLoader resources) {
        return JwtKeyLoader.load(settings, environment, resources);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyLoader.Keys keys) {
        RSAKey rsa = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).keyID("restnotes").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeyLoader.Keys keys, JwtSettings settings, Clock clock, UserAccountRepository users) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(settings.audience()));
        OAuth2TokenValidator<Jwt> identity = token -> validIdentity(token, clock, users);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(settings.issuer()), audience, identity));
        return decoder;
    }

    private OAuth2TokenValidatorResult validIdentity(Jwt token, Clock clock, UserAccountRepository users) {
        try {
            UUID id = UUID.fromString(token.getSubject());
            if (id.toString().equals(token.getSubject()) && token.getExpiresAt() != null
                    && token.getIssuedAt() != null && token.getExpiresAt().isAfter(token.getIssuedAt())
                    && !token.getIssuedAt().isAfter(clock.instant().plusSeconds(30))
                    && users.existsById(id)) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            // Invalid claims are reported without echoing the submitted token or subject.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid bearer token", null));
    }
}
