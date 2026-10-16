package com.scheideckerantunes.notes.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Passwords are never trimmed, logged or included in response serialization.
 *
 * @author Carlos Scheidecker
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 72) @WithinBcryptLimit
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {

    public LoginRequest {
        email = EmailAddress.normalize(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[password=REDACTED]";
    }
}
