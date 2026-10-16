package com.scheideckerantunes.notes.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scheideckerantunes.notes.auth.JwtKeyLoader;
import com.scheideckerantunes.notes.auth.JwtSettings;
import com.scheideckerantunes.notes.auth.TokenService;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Cryptographic policy tests use disposable RSA keys, a fixed clock and no database.
 *
 * @author Carlos Scheidecker
 */
class JwtConfigurationTest {
    @TempDir
    private Path directory;
    private static final Instant NOW = Instant.parse("2026-10-16T21:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private final JwtSettings settings = new JwtSettings("RestNotes", "RestNotes-api", 900, "", "");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private JwtEncoder encoder;
    private JwtDecoder decoder;

    @BeforeEach
    void configure() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        JwtConfiguration configuration = new JwtConfiguration();
        var keys = configuration.jwtKeys(settings, environment, new DefaultResourceLoader());
        encoder = configuration.jwtEncoder(keys);
        when(users.existsById(any(UUID.class))).thenReturn(true);
        decoder = configuration.jwtDecoder(keys, settings, clock, users);
    }

    @Test
    void issuesIdentityOnlyTokensWithBoundedLifetime() {
        var response = new TokenService(encoder, settings, clock).issue(USER_ID);
        var jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(jwt.getAudience()).containsExactly("RestNotes-api");
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getClaims()).doesNotContainKeys("roles", "teams", "password", "email");
        assertThat(response.toString().contains(response.accessToken())).isFalse();
    }

    @Test
    void rejectsWrongIssuerAndAudience() {
        rejected(claims().issuer("another-issuer").build());
        rejected(claims().audience(List.of("another-api")).build());
    }

    @Test
    void rejectsExpiredAndNotYetValidTokens() {
        rejected(claims().issuedAt(NOW.minusSeconds(900)).expiresAt(NOW.minusSeconds(60)).build());
        rejected(claims().notBefore(NOW.plusSeconds(60)).build());
        rejected(claims().issuedAt(NOW.plusSeconds(60)).build());
    }

    @Test
    void requiresExpiryIssuedAtAndCanonicalUserSubject() {
        rejected(claims().claims(map -> map.remove("exp")).build());
        rejected(claims().claims(map -> map.remove("iat")).build());
        rejected(claims().subject("not-a-uuid").build());
    }

    @Test
    void rejectsDeletedOrUnknownUsers() {
        when(users.existsById(USER_ID)).thenReturn(false);
        rejected(claims().build());
    }

    @Test
    void rejectsOtherSignatureAlgorithmsAndWrongSigningKeys() {
        String wrongAlgorithm = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS512).build(), claims().build())).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(wrongAlgorithm)).isInstanceOf(JwtException.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        var otherKeys = JwtKeyLoader.load(settings, environment, new DefaultResourceLoader());
        JwtEncoder otherEncoder = new JwtConfiguration().jwtEncoder(otherKeys);
        String wrongKey = otherEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims().build())).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(wrongKey)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
    }

    @Test
    void protectedProfilesNeverGenerateEphemeralKeys() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> JwtKeyLoader.load(settings, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Persistent");
        environment.setActiveProfiles("uat");
        assertThatThrownBy(() -> JwtKeyLoader.load(settings, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPartialKeyConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        JwtSettings partial = new JwtSettings("RestNotes", "RestNotes-api", 900, "file:/missing.pem", "");
        assertThatThrownBy(() -> JwtKeyLoader.load(partial, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("together");
    }

    @Test
    void protectedKeysSurviveEncoderDecoderRecreation() throws Exception {
        var provided = keyFiles(2048, "stable");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var config = new JwtConfiguration();
        var keys = JwtKeyLoader.load(provided, environment, new DefaultResourceLoader());
        String token = new TokenService(config.jwtEncoder(keys), settings, clock).issue(USER_ID).accessToken();
        var reloaded = JwtKeyLoader.load(provided, environment, new DefaultResourceLoader());
        assertThat(config.jwtDecoder(reloaded, settings, clock, users).decode(token).getSubject())
                .isEqualTo(USER_ID.toString());
    }

    @Test
    void rejectsWeakOrMismatchedKeyFilesAndRemoteLocations() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        JwtSettings weak = keyFiles(1024, "weak");
        assertThatThrownBy(() -> JwtKeyLoader.load(weak, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
        var first = keyFiles(2048, "first");
        var second = keyFiles(2048, "second");
        var mismatch = new JwtSettings(settings.issuer(), settings.audience(), 900,
                first.privateKeyLocation(), second.publicKeyLocation());
        assertThatThrownBy(() -> JwtKeyLoader.load(mismatch, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class);
        var remote = new JwtSettings(settings.issuer(), settings.audience(), 900,
                "https://invalid.test/private.pem", "https://invalid.test/public.pem");
        assertThatThrownBy(() -> JwtKeyLoader.load(remote, environment, new DefaultResourceLoader()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mounted");
    }

    private JwtSettings keyFiles(int bits, String prefix) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        var pair = generator.generateKeyPair();
        Path privateKey = directory.resolve(prefix + "-private.pem");
        Path publicKey = directory.resolve(prefix + "-public.pem");
        Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Files.writeString(publicKey, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        return new JwtSettings(settings.issuer(), settings.audience(), 900,
                privateKey.toUri().toString(), publicKey.toUri().toString());
    }

    private String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer(settings.issuer()).audience(List.of(settings.audience()))
                .subject(USER_ID.toString()).issuedAt(NOW).expiresAt(NOW.plusSeconds(900));
    }

    private void rejected(JwtClaimsSet claims) {
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
