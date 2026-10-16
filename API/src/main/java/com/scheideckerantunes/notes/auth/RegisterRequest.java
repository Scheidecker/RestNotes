package com.scheideckerantunes.notes.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * No role, membership, ID or password-hash input is accepted.
 *
 * @author Carlos Scheidecker
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 12, max = 72) @WithinBcryptLimit
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        @NotBlank @Size(max = 100) String fullName) {

    public RegisterRequest {
        email = EmailAddress.normalize(email);
        fullName = fullName == null ? null : fullName.strip();
    }

    @Override
    public String toString() {
        return "RegisterRequest[password=REDACTED]";
    }
}
