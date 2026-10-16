package com.scheideckerantunes.notes.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Row concurrency token, independent from note-history snapshot numbering.
 *
 * @author Carlos Scheidecker
 */
@MappedSuperclass
public abstract class VersionedEntity extends AuditedEntity {

    @Version
    @Column(nullable = false)
    private long version;

    public long getVersion() {
        return version;
    }
}
