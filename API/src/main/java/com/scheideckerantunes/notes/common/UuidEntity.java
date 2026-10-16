package com.scheideckerantunes.notes.common;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Uses ordinary, unswapped UUID bytes consistently across Hibernate and migrations.
 *
 * @author Carlos Scheidecker
 */
@MappedSuperclass
public abstract class UuidEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(columnDefinition = "BINARY(16)", updatable = false)
    private UUID id;

    public UUID getId() {
        return id;
    }
}
