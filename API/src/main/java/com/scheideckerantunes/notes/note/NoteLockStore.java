package com.scheideckerantunes.notes.note;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC joins the JPA transaction; writes require the authorized note mutex first.
 *
 * @author Carlos Scheidecker
 */
@Repository
public class NoteLockStore {
    private final JdbcTemplate jdbc;

    public NoteLockStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Instant now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",
                (row, index) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    public Optional<Lease> find(UUID noteId) {
        return jdbc.query("SELECT BIN_TO_UUID(owner_id) AS owner_id, token_digest, acquired_at, expires_at "
                + "FROM note_locks WHERE note_id = UUID_TO_BIN(?)",
                (row, index) -> new Lease(UUID.fromString(row.getString("owner_id")), row.getString("token_digest"),
                        instant(row, "acquired_at"), instant(row, "expires_at")), noteId.toString())
                .stream().findFirst();
    }

    public void acquire(UUID noteId, UUID owner, String digest, Instant at, Instant expires) {
        delete(noteId);
        jdbc.update("INSERT INTO note_locks(note_id, owner_id, token_digest, acquired_at, expires_at) "
                + "VALUES(UUID_TO_BIN(?), UUID_TO_BIN(?), ?, ?, ?)", noteId.toString(), owner.toString(), digest,
                utc(at), utc(expires));
    }

    public void renew(UUID noteId, Instant expires) {
        jdbc.update("UPDATE note_locks SET expires_at = ? WHERE note_id = UUID_TO_BIN(?)",
                utc(expires), noteId.toString());
    }

    public void delete(UUID noteId) {
        jdbc.update("DELETE FROM note_locks WHERE note_id = UUID_TO_BIN(?)", noteId.toString());
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        return row.getObject(name, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /**
     * Persisted lease metadata never exposes its verifier in string output.
     *
     * @author Carlos Scheidecker
     */
    public record Lease(UUID ownerId, String digest, Instant acquiredAt, Instant expiresAt) {
        public boolean active(Instant at) {
            return expiresAt.isAfter(at);
        }

        @Override
        public String toString() {
            return "Lease[ownerId=" + ownerId + ", digest=REDACTED]";
        }
    }
}
