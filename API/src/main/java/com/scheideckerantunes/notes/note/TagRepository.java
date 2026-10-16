package com.scheideckerantunes.notes.note;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal team-scoped label lookup.
 *
 * @author Carlos Scheidecker
 */
public interface TagRepository extends JpaRepository<Tag, UUID> {
    Optional<Tag> findByTeamIdAndNormalizedName(UUID teamId, String normalizedName);
}
