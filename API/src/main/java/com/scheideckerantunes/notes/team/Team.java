package com.scheideckerantunes.notes.team;

import com.scheideckerantunes.notes.common.VersionedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Team aggregate; membership administration will serialize on this aggregate.
 *
 * @author Carlos Scheidecker
 */
@Entity
@Table(name = "teams")
public class Team extends VersionedEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    protected Team() {
    }

    public Team(String name, String description) {
        this.name = Objects.requireNonNull(name);
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}
