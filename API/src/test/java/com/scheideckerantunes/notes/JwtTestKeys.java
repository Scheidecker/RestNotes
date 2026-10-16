package com.scheideckerantunes.notes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Disposable local key files; never production keys and never written to test logs.
 *
 * @author Carlos Scheidecker
 */
final class JwtTestKeys {
    private static final Path PRIVATE_KEY;
    private static final Path PUBLIC_KEY;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            Path directory = Files.createTempDirectory("restnotes-jwt-test-");
            directory.toFile().deleteOnExit();
            PRIVATE_KEY = directory.resolve("private.pem");
            PUBLIC_KEY = directory.resolve("public.pem");
            Files.writeString(PRIVATE_KEY, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(PUBLIC_KEY, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            PRIVATE_KEY.toFile().deleteOnExit();
            PUBLIC_KEY.toFile().deleteOnExit();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create disposable JWT test keys");
        }
    }

    private JwtTestKeys() {
    }

    static String privateLocation() {
        return PRIVATE_KEY.toUri().toString();
    }

    static String publicLocation() {
        return PUBLIC_KEY.toUri().toString();
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
    }
}
