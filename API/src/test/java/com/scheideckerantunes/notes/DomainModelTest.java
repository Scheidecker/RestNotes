package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import com.scheideckerantunes.notes.note.Tag;
import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.user.UserAccount;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests do not start Spring, Docker or a database.
 *
 * @author Carlos Scheidecker
 */
class DomainModelTest {

    @Test
    void normalizesEmailAndTags() {
        UserAccount user = new UserAccount(" ADMIN@scheideckerantunes.com ", "unused-test-hash", "Admin");
        assertThat(user.getEmail()).isEqualTo("admin@scheideckerantunes.com");
        Tag tag = new Tag(UUID.randomUUID(), " OnBoarding ");
        assertThat(tag.getNormalizedName()).isEqualTo("onboarding");
    }

    @Test
    void membershipKeysHaveValueEquality() {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        assertThat(new TeamMemberId(teamId, userId)).isEqualTo(new TeamMemberId(teamId, userId));
        assertThat(new TeamMemberId(teamId, userId)).isNotEqualTo(new TeamMemberId(userId, teamId));
    }
}
