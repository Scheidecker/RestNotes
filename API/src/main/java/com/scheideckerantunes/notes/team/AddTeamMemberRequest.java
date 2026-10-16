package com.scheideckerantunes.notes.team;

import com.scheideckerantunes.notes.auth.EmailAddress;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Directly adds an already registered identity; this does not send an invitation.
 *
 * @author Carlos Scheidecker
 */
public record AddTeamMemberRequest(@NotBlank @Email @Size(max = 255) String email, @NotNull TeamRole role) {
    public AddTeamMemberRequest {
        email = EmailAddress.normalize(email);
    }
}
