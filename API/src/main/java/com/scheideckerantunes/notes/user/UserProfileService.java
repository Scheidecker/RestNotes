package com.scheideckerantunes.notes.user;

import com.scheideckerantunes.notes.common.PageResponse;
import com.scheideckerantunes.notes.team.TeamRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns only the authenticated user's identity and currently visible affiliations.
 *
 * @author Carlos Scheidecker
 */
@Service
@Transactional(readOnly = true)
public class UserProfileService {
    private final UserAccountRepository users;
    private final TeamRepository teams;

    public UserProfileService(UserAccountRepository users, TeamRepository teams) {
        this.users = users;
        this.teams = teams;
    }

    public UserProfile profile(UUID userId) {
        return UserProfile.from(users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("Invalid bearer token")));
    }

    public PageResponse<TeamAffiliation> teams(UUID userId, int page, int size) {
        return PageResponse.from(teams.findAffiliations(userId,
                PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id")))));
    }
}
