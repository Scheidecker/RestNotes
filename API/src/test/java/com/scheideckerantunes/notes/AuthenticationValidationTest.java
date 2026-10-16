package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import com.scheideckerantunes.notes.auth.LoginRequest;
import com.scheideckerantunes.notes.auth.RegisterRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/**
 * Validates normalization, Unicode password limits and redacted request representations.
 *
 * @author Carlos Scheidecker
 */
class AuthenticationValidationTest {
    @Test
    void registrationEnforcesUtf8ByteLimitAndMinimumLength() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new RegisterRequest(
                    "test@scheideckerantunes.com", "é".repeat(37), "Test"))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest(
                    "test@scheideckerantunes.com", "é".repeat(36), "Test"))).isEmpty();
            assertThat(validator.validate(new RegisterRequest(
                    "test@scheideckerantunes.com", "short", "Test"))).isNotEmpty();
        }
    }

    @Test
    void normalizesIdentityButNeverTrimsPassword() {
        String password = " correct test password ";
        var register = new RegisterRequest(" TEST@scheideckerantunes.com ", password, " Test ");
        assertThat(register.email()).isEqualTo("test@scheideckerantunes.com");
        assertThat(register.fullName()).isEqualTo("Test");
        assertThat(register.password().equals(password)).isTrue();
        assertThat(register.toString().contains(password)).isFalse();
        assertThat(new LoginRequest(register.email(), password).toString().contains(password)).isFalse();
    }
}
