package com.scheideckerantunes.notes.activity;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Internal activity storage; access checks belong to future activity services.
 *
 * @author Carlos Scheidecker
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {
    Page<ActivityLog> findByTeamId(UUID teamId, Pageable pageable);
}
