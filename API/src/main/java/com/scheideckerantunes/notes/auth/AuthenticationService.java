package com.scheideckerantunes.notes.auth;

import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import com.scheideckerantunes.notes.user.UserProfile;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration grants no memberships; login uses Spring's constant-time failure path.
 *
 * @author Carlos Scheidecker
 */
@Service
public class AuthenticationService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authentication;
    private final TokenService tokens;
    private final boolean registrationEnabled;

    public AuthenticationService(UserAccountRepository users, PasswordEncoder passwords,
            AuthenticationManager authentication, TokenService tokens,
            @Value("${restnotes.security.registration-enabled:false}") boolean registrationEnabled) {
        this.users = users;
        this.passwords = passwords;
        this.authentication = authentication;
        this.tokens = tokens;
        this.registrationEnabled = registrationEnabled;
    }

    @Transactional
    public UserProfile register(RegisterRequest request) {
        if (!registrationEnabled) {
            throw new RegistrationDisabledException();
        }
        if (users.findByEmail(request.email()).isPresent()) {
            throw new DuplicateAccountException();
        }
        UserAccount account = new UserAccount(
                request.email(), passwords.encode(request.password()), request.fullName());
        try {
            return UserProfile.from(users.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            // The database unique constraint resolves the race after the early duplicate check.
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                        && constraint.getConstraintName() != null
                        && constraint.getConstraintName().contains("uq_users_email")) {
                    throw new DuplicateAccountException();
                }
                cause = cause.getCause();
            }
            throw exception;
        }
    }

    public TokenResponse login(LoginRequest request) {
        var result = authentication.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        return tokens.issue(UUID.fromString(result.getName()));
    }
}
