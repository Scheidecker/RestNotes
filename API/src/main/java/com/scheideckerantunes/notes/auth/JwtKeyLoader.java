package com.scheideckerantunes.notes.auth;

import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.util.StringUtils;

/**
 * Loads PKCS#8 private/X.509 public PEM files without logging key material.
 *
 * @author Carlos Scheidecker
 */
public final class JwtKeyLoader {

    private JwtKeyLoader() {
    }

    public static Keys load(JwtSettings settings, Environment environment, ResourceLoader resources) {
        boolean privateProvided = StringUtils.hasText(settings.privateKeyLocation());
        boolean publicProvided = StringUtils.hasText(settings.publicKeyLocation());
        if (privateProvided != publicProvided) {
            throw new IllegalStateException("Both JWT key locations must be configured together");
        }
        if (!privateProvided) {
            if (!environment.acceptsProfiles(Profiles.of("(dev | test) & !uat & !prod"))) {
                throw new IllegalStateException("Persistent RSA key files are required outside development/test");
            }
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var pair = generator.generateKeyPair();
                return new Keys((RSAPrivateKey) pair.getPrivate(), (RSAPublicKey) pair.getPublic());
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to generate the development JWT signing key");
            }
        }
        if (!settings.privateKeyLocation().startsWith("file:") || !settings.publicKeyLocation().startsWith("file:")) {
            throw new IllegalStateException("JWT key locations must reference mounted file: resources");
        }
        try (InputStream privateFile = resources.getResource(settings.privateKeyLocation()).getInputStream();
                InputStream publicFile = resources.getResource(settings.publicKeyLocation()).getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(privateFile);
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(publicFile);
            if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() < 2048
                    || !publicKey.getModulus().equals(privateKey.getModulus())) {
                throw new IllegalStateException("Invalid RSA key pair");
            }
            return new Keys(privateKey, publicKey);
        } catch (Exception exception) {
            throw new IllegalStateException("JWT key files must contain a matching RSA pair of at least 2048 bits");
        }
    }

    /**
     * Deliberately not a record: generated toString must never display key contents.
     *
     * @author Carlos Scheidecker
     */
    public static final class Keys {
        private final RSAPrivateKey privateKey;
        private final RSAPublicKey publicKey;

        Keys(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        public RSAPrivateKey privateKey() {
            return privateKey;
        }

        public RSAPublicKey publicKey() {
            return publicKey;
        }
    }
}
