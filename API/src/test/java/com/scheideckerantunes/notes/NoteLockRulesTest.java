package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scheideckerantunes.notes.note.NoteLockResponse;
import com.scheideckerantunes.notes.note.NoteLockStatus;
import com.scheideckerantunes.notes.note.NoteLockStore;
import com.scheideckerantunes.notes.note.NoteLockTokens;
import com.scheideckerantunes.notes.note.NoteLocks;
import com.scheideckerantunes.notes.note.NoteOperationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Expiry boundary and token fencing without sleeps or database dependencies.
 *
 * @author Carlos Scheidecker
 */
class NoteLockRulesTest {
    @Test
    void credentialsAreCanonicalRandomAndComparedByDigest() {
        String token = NoteLockTokens.generate();
        NoteLockTokens.validate(token);
        assertThat(token.length()).isEqualTo(43);
        assertThat(NoteLockTokens.generate().equals(token)).isFalse();
        assertThat(NoteLockTokens.digest(token).length()).isEqualTo(64);
        assertThat(NoteLockTokens.matches(token, NoteLockTokens.digest(token))).isTrue();
        assertThat(NoteLockTokens.matches(NoteLockTokens.generate(), NoteLockTokens.digest(token))).isFalse();
        assertThat(NoteLockTokens.matches(null, NoteLockTokens.digest(token))).isFalse();
    }

    @Test
    void malformedCredentialsAreRejectedWithoutEchoingThem() {
        NoteLockTokens.validate(null);
        for (String token : List.of("", " ", "x", "a".repeat(44), "a".repeat(42) + "=", "a".repeat(43))) {
            assertThatThrownBy(() -> NoteLockTokens.validate(token)).isInstanceOf(NoteOperationException.class)
                    .hasMessage("INVALID_LOCK_TOKEN");
        }
    }

    @Test
    void deadlineIsExclusiveAndPublicStatusHidesExpiredMetadata() {
        Instant start = Instant.parse("2026-10-16T00:00:00Z");
        var lease = new NoteLockStore.Lease(UUID.randomUUID(), "redacted", start, start.plusSeconds(300));
        assertThat(lease.active(start.plusSeconds(300).minusNanos(1))).isTrue();
        assertThat(lease.active(start.plusSeconds(300))).isFalse();
        assertThat(NoteLockStatus.from(lease, start).locked()).isTrue();
        var expired = NoteLockStatus.from(lease, start.plusSeconds(300));
        assertThat(expired.locked()).isFalse();
        assertThat(expired.ownerId()).isNull();
        assertThat(expired.expiresAt()).isNull();
        assertThat(NoteLockStatus.from(null, start).locked()).isFalse();
    }

    @Test
    void staleTokenMustNotBecomeAnUnlockedWrite() {
        NoteLockStore store = mock(NoteLockStore.class);
        UUID note = UUID.randomUUID();
        when(store.now()).thenReturn(Instant.now());
        when(store.find(note)).thenReturn(Optional.empty());
        var guard = new NoteLocks(store);
        guard.requireWrite(note, UUID.randomUUID(), null);
        assertThatThrownBy(() -> guard.requireWrite(note, UUID.randomUUID(), NoteLockTokens.generate()))
                .isInstanceOf(NoteOperationException.class).hasMessage("STALE_LOCK_TOKEN");
    }

    @Test
    void ownerIdentityAndCredentialAreBothRequired() {
        UUID owner = UUID.randomUUID();
        String token = NoteLockTokens.generate();
        Instant now = Instant.now();
        var lease = new NoteLockStore.Lease(owner, NoteLockTokens.digest(token), now, now.plusSeconds(300));
        var guard = new NoteLocks(mock(NoteLockStore.class));
        guard.requireHolder(lease, owner, token);
        assertThatThrownBy(() -> guard.requireHolder(lease, UUID.randomUUID(), token))
                .hasMessage("NOTE_LOCKED");
        assertThatThrownBy(() -> guard.requireHolder(lease, owner, null)).hasMessage("NOTE_LOCKED");
        assertThatThrownBy(() -> guard.requireHolder(lease, owner, NoteLockTokens.generate()))
                .hasMessage("STALE_LOCK_TOKEN");
    }

    @Test
    void stringRepresentationsRedactCredentials() {
        UUID owner = UUID.randomUUID();
        String token = NoteLockTokens.generate();
        Instant now = Instant.now();
        assertThat(new NoteLockResponse(owner, now, now.plusSeconds(300), token).toString())
                .contains("token=REDACTED");
        assertThat(new NoteLockStore.Lease(owner, NoteLockTokens.digest(token), now, now.plusSeconds(300)).toString())
                .contains("digest=REDACTED");
    }
}
