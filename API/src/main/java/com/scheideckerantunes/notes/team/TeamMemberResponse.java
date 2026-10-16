package com.scheideckerantunes.notes.team;

import com.scheideckerantunes.notes.user.UserAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * Member information visible only inside an authorized team; no credentials.
 *
 * @author Carlos Scheidecker
 */
public record TeamMemberResponse(UUID userId, String email, String fullName, TeamRole role, Instant joinedAt) {
    public static TeamMemberResponse from(UserAccount account, TeamMember member) {
        return new TeamMemberResponse(account.getId(), account.getEmail(), account.getFullName(),
                member.getRole(), member.getJoinedAt());
    }
}
