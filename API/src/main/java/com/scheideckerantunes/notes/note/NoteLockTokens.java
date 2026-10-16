package com.scheideckerantunes.notes.note;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque 256-bit per-acquisition credentials; only their SHA-256 digest is stored.
 *
 * @author Carlos Scheidecker
 */
public final class NoteLockTokens {
    public static final String HEADER = "Note-Lock-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private NoteLockTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void validate(String token) {
        if (token != null && (!token.matches("[A-Za-z0-9_-]{43}")
                || !Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Base64.getUrlDecoder().decode(token)).equals(token))) {
            throw new NoteOperationException(NoteOperationException.Code.INVALID_LOCK_TOKEN);
        }
    }

    public static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required digest algorithm unavailable", exception);
        }
    }

    public static boolean matches(String token, String digest) {
        return token != null && MessageDigest.isEqual(
                digest(token).getBytes(StandardCharsets.US_ASCII), digest.getBytes(StandardCharsets.US_ASCII));
    }
}
