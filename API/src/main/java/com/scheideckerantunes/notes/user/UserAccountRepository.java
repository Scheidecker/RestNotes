package com.scheideckerantunes.notes.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal persistence API; not exported as an HTTP endpoint.
 *
 * @author Carlos Scheidecker
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByEmail(String normalizedEmail);
}
