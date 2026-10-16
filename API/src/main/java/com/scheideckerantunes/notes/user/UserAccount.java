package com.scheideckerantunes.notes.user;

import com.scheideckerantunes.notes.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.Objects;

/**
 * Local identity; team roles are memberships, never global user roles.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "users")
public class UserAccount extends AuditedEntity {

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    @Column(nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String fullName;

    protected UserAccount() {
    }

    public UserAccount(String email, String passwordHash, String fullName) {
        this.email = Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.fullName = Objects.requireNonNull(fullName);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }
}
