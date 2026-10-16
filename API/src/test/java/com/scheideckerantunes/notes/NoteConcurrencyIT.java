package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.note.CreateNoteRequest;
import com.scheideckerantunes.notes.note.AddTagRequest;
import com.scheideckerantunes.notes.note.TagRepository;
import com.scheideckerantunes.notes.note.NoteEtags;
import com.scheideckerantunes.notes.note.NoteLockService;
import com.scheideckerantunes.notes.note.NoteLockStore;
import com.scheideckerantunes.notes.note.NoteLockTokens;
import com.scheideckerantunes.notes.note.NoteOperationException;
import com.scheideckerantunes.notes.note.NoteRepository;
import com.scheideckerantunes.notes.note.NoteService;
import com.scheideckerantunes.notes.note.PatchNoteRequest;
import com.scheideckerantunes.notes.notebook.CreateNotebookRequest;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookService;
import com.scheideckerantunes.notes.notebook.NotebookShareRequest;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.team.UpdateTeamMemberRequest;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrent commands use separate real transactions; only these tests' new fixtures are cleaned.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@ActiveProfiles("test")
class NoteConcurrencyIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = id(1, 1);
    private static final UUID RECIPIENT = id(1, 2);
    private static final UUID OWNER_TEAM = id(2, 1);
    private static final UUID RECIPIENT_TEAM = id(2, 2);
    @Autowired
    private NoteService service;
    @Autowired
    private NoteLockService leases;
    @Autowired
    private NoteLockStore leaseStore;
    @Autowired
    private NoteRepository notes;
    @Autowired
    private NoteHistoryRepository history;
    @Autowired
    private TagRepository catalog;
    @Autowired
    private NotebookService notebooks;
    @Autowired
    private TeamService teams;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void competingEditsProduceOneRevisionWithoutDuplicateHistoryVersions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String tag = fixture.tag(OWNER);
            assertThat(race(
                    () -> result(() -> service.patch(fixture.noteId, OWNER, tag, content("First"))),
                    () -> result(() -> service.patch(fixture.noteId, OWNER, tag, content("Second")))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isEqualTo(1);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(2);
            assertThat(fixture.actionCount("NOTE_UPDATED")).isEqualTo(1);
        }
    }

    @Test
    void editAndArchiveCannotBothCommitAgainstTheSameVersion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String tag = fixture.tag(OWNER);
            var outcomes = race(
                    () -> result(() -> service.patch(fixture.noteId, OWNER, tag, content("Changed"))),
                    () -> result(() -> service.archive(fixture.noteId, OWNER, tag)));
            boolean archived = notes.findById(fixture.noteId).orElseThrow().getArchivedAt() != null;
            assertThat(outcomes).containsExactlyInAnyOrder(200, archived ? 404 : 412);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(archived ? 1 : 2);
        }
    }

    @Test
    void grantRevocationSerializesWithSharedEditorWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.share();
            String noteTag = fixture.tag(RECIPIENT);
            String bookTag = fixture.bookTag();
            var outcomes = race(
                    () -> result(() -> service.patch(fixture.noteId, RECIPIENT, noteTag, content("Shared"))),
                    () -> result(() -> notebooks.revoke(fixture.bookId, OWNER, RECIPIENT_TEAM, bookTag)));
            assertThat(outcomes.get(0)).isIn(200, 404);
            assertThat(outcomes.get(1)).isEqualTo(200);
            assertThatThrownBy(() -> service.detail(fixture.noteId, RECIPIENT))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(outcomes.get(0) == 200 ? 2 : 1);
        }
    }

    @Test
    void membershipDowngradeSerializesWithSharedEditorWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UserAccount member = fixture.user();
            teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), TeamRole.EDITOR));
            fixture.share();
            String tag = fixture.tag(member.getId());
            var outcomes = race(
                    () -> result(() -> service.patch(fixture.noteId, member.getId(), tag, content("Shared"))),
                    () -> result(() -> teams.updateMember(RECIPIENT_TEAM, RECIPIENT, member.getId(),
                            new UpdateTeamMemberRequest(TeamRole.VIEWER))));
            assertThat(outcomes.get(0)).isIn(200, 403);
            assertThat(outcomes.get(1)).isEqualTo(200);
            assertThat(service.detail(fixture.noteId, member.getId()).accessLevel()).isEqualTo(TeamRole.VIEWER);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(outcomes.get(0) == 200 ? 2 : 1);
        }
    }

    @Test
    void failedCreationRollsBackNoteSnapshotAndActivity() {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<UUID> created = new AtomicReference<>();
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                created.set(service.create(fixture.bookId, OWNER, new CreateNoteRequest("Rollback", "", false)).id());
                throw new IllegalStateException("Abort this test transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(notes.existsById(created.get())).isFalse();
            assertThat(history.findLatestVersion(created.get())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE entity_id = UUID_TO_BIN(?)",
                    Integer.class, created.get().toString())).isZero();
        }
    }

    @Test
    void failedUpdateRollsBackContentVersionSnapshotAndActivity() {
        try (Fixture fixture = new Fixture()) {
            String tag = fixture.tag(OWNER);
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                service.patch(fixture.noteId, OWNER, tag, content("Must roll back"));
                throw new IllegalStateException("Abort this test transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isZero();
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
            assertThat(fixture.actionCount("NOTE_UPDATED")).isZero();
        }
    }

    @Test
    void tagAndContentChangesCannotBothCommitAgainstOneValidator() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String tag = fixture.tag(OWNER);
            String name = fixture.label("race");
            assertThat(race(
                    () -> result(() -> service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(name))),
                    () -> result(() -> service.patch(fixture.noteId, OWNER, tag, content("Concurrent content")))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isEqualTo(1);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(2);
        }
    }

    @Test
    void competingTagsHaveOneWinnerAndNoOrphanCatalogEntry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String tag = fixture.tag(OWNER);
            String first = fixture.label("first");
            String second = fixture.label("second");
            assertThat(race(
                    () -> result(() -> service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(first))),
                    () -> result(() -> service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(second)))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(service.detail(fixture.noteId, OWNER).tags()).hasSize(1);
            int rows = (catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, first).isPresent() ? 1 : 0)
                    + (catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, second).isPresent() ? 1 : 0);
            assertThat(rows).isEqualTo(1);
            assertThat(fixture.actionCount("NOTE_TAGGED")).isEqualTo(1);
        }
    }

    @Test
    void concurrentBoundaryAddsCannotExceedTwentyTags() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int index = 0; index < 19; index++) {
                service.addTag(fixture.noteId, OWNER, fixture.tag(OWNER),
                        new AddTagRequest(fixture.label("base-" + index)));
            }
            String tag = fixture.tag(OWNER);
            String first = fixture.label("twentieth");
            String second = fixture.label("overflow");
            assertThat(race(
                    () -> result(() -> service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(first))),
                    () -> result(() -> service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(second)))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(service.detail(fixture.noteId, OWNER).tags()).hasSize(20);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(21);
        }
    }

    @Test
    void concurrentTeamCatalogAliasesAcrossNotesReuseOneRow() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            String name = first.label("café");
            String alias = name.replace("café", "CAFE");
            String firstTag = first.tag(OWNER);
            String secondTag = second.tag(OWNER);
            assertThat(race(
                    () -> result(() -> service.addTag(first.noteId, OWNER, firstTag, new AddTagRequest(name))),
                    () -> result(() -> service.addTag(second.noteId, OWNER, secondTag, new AddTagRequest(alias)))))
                    .containsExactlyInAnyOrder(200, 200);
            assertThat(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, name).orElseThrow().getId())
                    .isEqualTo(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, alias).orElseThrow().getId());
            assertThat(service.detail(first.noteId, OWNER).tags())
                    .isEqualTo(service.detail(second.noteId, OWNER).tags());
        }
    }

    @Test
    void failedTagAdditionRollsBackCatalogAssociationVersionHistoryAndActivity() {
        try (Fixture fixture = new Fixture()) {
            String name = fixture.label("rollback-add");
            String tag = fixture.tag(OWNER);
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                service.addTag(fixture.noteId, OWNER, tag, new AddTagRequest(name));
                throw new IllegalStateException("Abort this test transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(service.detail(fixture.noteId, OWNER).tags()).isEmpty();
            assertThat(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, name)).isEmpty();
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isZero();
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
            assertThat(fixture.actionCount("NOTE_TAGGED")).isZero();
        }
    }

    @Test
    void failedTagRemovalRestoresAssociationVersionHistoryAndActivity() {
        try (Fixture fixture = new Fixture()) {
            String name = fixture.label("rollback-del");
            service.addTag(fixture.noteId, OWNER, fixture.tag(OWNER), new AddTagRequest(name));
            String tag = fixture.tag(OWNER);
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                service.removeTag(fixture.noteId, OWNER, name, tag);
                throw new IllegalStateException("Abort this test transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(service.detail(fixture.noteId, OWNER).tags()).containsExactly(name);
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isEqualTo(1);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(2);
            assertThat(fixture.actionCount("NOTE_UNTAGGED")).isZero();
        }
    }
    @Test
    void competingAcquisitionsEvenByTheSameUserHaveOneWinner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            assertThat(race(
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, null)),
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, null))))
                    .containsExactlyInAnyOrder(200, 423);
            assertThat(notes.findById(fixture.noteId).orElseThrow().getVersion()).isZero();
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
        }
    }

    @Test
    void acquisitionAndUnlockedEditSerializeWithoutBypassingTheLease() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            var outcomes = race(
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, null)),
                    () -> result(() -> service.patch(fixture.noteId, OWNER, etag, content("Concurrent edit"))));
            if (outcomes.get(0) == 200) {
                assertThat(outcomes.get(1)).isEqualTo(423);
                assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
            } else {
                assertThat(outcomes).containsExactly(412, 200);
                assertThat(leaseStore.find(fixture.noteId)).isEmpty();
                assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(2);
            }
        }
    }

    @Test
    void expiredTakeoverHasOneWinnerAndFencesOldCredential() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            String old = leases.acquire(fixture.noteId, OWNER, etag, null).response().token();
            fixture.expireLease();
            assertThat(race(
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, null)),
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, null))))
                    .containsExactlyInAnyOrder(200, 423);
            assertThat(NoteLockTokens.matches(old, leaseStore.find(fixture.noteId).orElseThrow().digest())).isFalse();
            assertThatThrownBy(() -> leases.release(fixture.noteId, OWNER, etag, old))
                    .hasMessage("STALE_LOCK_TOKEN");
        }
    }

    @Test
    void renewalAndReleaseNeverResurrectAReleasedGeneration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            String token = leases.acquire(fixture.noteId, OWNER, etag, null).response().token();
            var outcomes = race(
                    () -> result(() -> leases.acquire(fixture.noteId, OWNER, etag, token)),
                    () -> result(() -> leases.release(fixture.noteId, OWNER, etag, token)));
            assertThat(outcomes.get(0)).isIn(200, 409);
            assertThat(outcomes.get(1)).isEqualTo(200);
            assertThat(leaseStore.find(fixture.noteId)).isEmpty();
        }
    }

    @Test
    void grantRevocationSerializesWithLeaseRenewalAndCannotRetainWriteAuthority() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.share();
            String etag = fixture.tag(RECIPIENT);
            String bookTag = fixture.bookTag();
            String token = leases.acquire(fixture.noteId, RECIPIENT, etag, null).response().token();
            var outcomes = race(
                    () -> result(() -> leases.acquire(fixture.noteId, RECIPIENT, etag, token)),
                    () -> result(() -> notebooks.revoke(fixture.bookId, OWNER, RECIPIENT_TEAM, bookTag)));
            assertThat(outcomes.get(0)).isIn(200, 404);
            assertThat(outcomes.get(1)).isEqualTo(200);
            assertThatThrownBy(() -> leases.release(fixture.noteId, RECIPIENT, etag, token))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
        }
    }

    @Test
    void membershipDowngradeSerializesWithLeaseRenewal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var member = fixture.user();
            teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), TeamRole.EDITOR));
            fixture.share();
            String etag = fixture.tag(member.getId());
            String token = leases.acquire(fixture.noteId, member.getId(), etag, null).response().token();
            var outcomes = race(
                    () -> result(() -> leases.acquire(fixture.noteId, member.getId(), etag, token)),
                    () -> result(() -> teams.updateMember(RECIPIENT_TEAM, RECIPIENT, member.getId(),
                            new UpdateTeamMemberRequest(TeamRole.VIEWER))));
            assertThat(outcomes.get(0)).isIn(200, 403);
            assertThat(outcomes.get(1)).isEqualTo(200);
            assertThatThrownBy(() -> leases.release(fixture.noteId, member.getId(), etag, token))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void acquisitionRenewalAndReleaseRollBackWithTheirEnclosingTransaction() {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                leases.acquire(fixture.noteId, OWNER, etag, null);
                throw new IllegalStateException("Abort fixture transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(leaseStore.find(fixture.noteId)).isEmpty();
            String token = leases.acquire(fixture.noteId, OWNER, etag, null).response().token();
            var original = leaseStore.find(fixture.noteId).orElseThrow();
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                leases.acquire(fixture.noteId, OWNER, etag, token);
                throw new IllegalStateException("Abort fixture transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(leaseStore.find(fixture.noteId).orElseThrow().expiresAt()).isEqualTo(original.expiresAt());
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                leases.release(fixture.noteId, OWNER, etag, token);
                throw new IllegalStateException("Abort fixture transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(leaseStore.find(fixture.noteId)).isPresent();
        }
    }

    @Test
    void holderContentAndArchiveRollbackRestoresBothJpaAndJdbcState() {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.tag(OWNER);
            String token = leases.acquire(fixture.noteId, OWNER, etag, null).response().token();
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                service.patch(fixture.noteId, OWNER, etag, content("Must roll back"), token);
                throw new IllegalStateException("Abort fixture transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                service.archive(fixture.noteId, OWNER, etag, token);
                throw new IllegalStateException("Abort fixture transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(leaseStore.find(fixture.noteId)).isPresent();
            assertThat(notes.findById(fixture.noteId).orElseThrow().getArchivedAt()).isNull();
            assertThat(fixture.tag(OWNER)).isEqualTo(etag);
            assertThat(history.findLatestVersion(fixture.noteId)).isEqualTo(1);
            assertThat(fixture.actionCount("NOTE_UPDATED")).isZero();
            assertThat(fixture.actionCount("NOTE_ARCHIVED")).isZero();
        }
    }

    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private PatchNoteRequest content(String text) {
        PatchNoteRequest request = new PatchNoteRequest();
        request.setContent(text);
        return request;
    }

    private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                return first.call();
            });
            var b = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private int result(Callable<?> operation) throws Exception {
        try {
            operation.call();
            return 200;
        } catch (NoteOperationException exception) {
            return exception.getCode().status().value();
        } catch (ResourceNotFoundException exception) {
            return 404;
        } catch (AccessDeniedException exception) {
            return 403;
        }
    }

    /**
     * Seeded users/teams are retained; only fresh notebook content and associated fixture users are removed.
     *
     * @author Carlos Scheidecker
     */
    private final class Fixture implements AutoCloseable {
        private final UUID bookId = notebooks.create(OWNER,
                new CreateNotebookRequest(OWNER_TEAM, "Concurrent notes", null)).id();
        private final UUID noteId = service.create(bookId, OWNER, new CreateNoteRequest("Original", "", false)).id();
        private final List<UUID> userIds = new ArrayList<>();
        private final String tagPrefix = UUID.randomUUID() + "-";
        private final List<String> tagNames = new ArrayList<>();

        String label(String suffix) {
            String name = tagPrefix + suffix;
            tagNames.add(name);
            return name;
        }

        String tag(UUID actor) {
            return NoteEtags.from(service.detail(noteId, actor), actor);
        }

        String bookTag() {
            return NotebookEtags.value(bookId, notebooks.detail(bookId, OWNER).version(), OWNER, TeamRole.ADMIN);
        }

        void share() {
            notebooks.share(bookId, OWNER, bookTag(),
                    new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR));
        }
        void expireLease() {
            jdbc.update("UPDATE note_locks SET acquired_at = UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE, "
                    + "expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE note_id = UUID_TO_BIN(?)",
                    noteId.toString());
        }

        UserAccount user() {
            var account = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                    users.findById(OWNER).orElseThrow().getPasswordHash(), "Concurrent writer"));
            userIds.add(account.getId());
            return account;
        }

        int actionCount(String action) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs "
                    + "WHERE entity_id = UUID_TO_BIN(?) AND action = ?", Integer.class, noteId.toString(), action);
        }

        @Override
        public void close() {
            transaction().executeWithoutResult(status -> {
                jdbc.update("DELETE FROM note_locks WHERE note_id = UUID_TO_BIN(?)", noteId.toString());
                jdbc.update("DELETE FROM note_history WHERE note_id = UUID_TO_BIN(?)", noteId.toString());
                jdbc.update("DELETE FROM activity_logs WHERE entity_id IN (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                        noteId.toString(), bookId.toString());
                jdbc.update("DELETE FROM note_tags WHERE note_id = UUID_TO_BIN(?)", noteId.toString());
                jdbc.update("DELETE FROM notes WHERE notebook_id = UUID_TO_BIN(?)", bookId.toString());
                jdbc.update("DELETE FROM notebook_shares WHERE notebook_id = UUID_TO_BIN(?)", bookId.toString());
                jdbc.update("DELETE FROM notebooks WHERE id = UUID_TO_BIN(?)", bookId.toString());
                for (String name : tagNames) {
                    jdbc.update("DELETE FROM tags WHERE team_id = UUID_TO_BIN(?) AND normalized_name = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM note_tags WHERE tag_id = tags.id)",
                            OWNER_TEAM.toString(), name);
                }
                for (UUID userId : userIds) {
                    jdbc.update("DELETE FROM activity_logs WHERE entity_type = 'membership' "
                            + "AND entity_id = UUID_TO_BIN(?)",
                            userId.toString());
                    jdbc.update("DELETE FROM team_members WHERE user_id = UUID_TO_BIN(?)", userId.toString());
                    users.deleteById(userId);
                }
            });
        }
    }

    private static UUID id(int type, int index) {
        return UUID.fromString(type + "0000000-0000-4000-8000-00000000000" + index);
    }
}
