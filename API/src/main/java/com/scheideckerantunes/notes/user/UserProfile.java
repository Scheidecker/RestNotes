package com.scheideckerantunes.notes.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe profile representation, without credentials or unbounded memberships.
 *
 * @author Carlos Scheidecker
 */
public record UserProfile(UUID id, String email, String fullName, Instant createdAt) {
    public static UserProfile from(UserAccount account) {
        return new UserProfile(account.getId(), account.getEmail(), account.getFullName(), account.getCreatedAt());
    }
}
