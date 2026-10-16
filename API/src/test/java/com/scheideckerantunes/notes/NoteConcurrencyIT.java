package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.note.CreateNoteRequest;
import com.scheideckerantunes.notes.note.NoteEtags;
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
    private NoteRepository notes;
    @Autowired
    private NoteHistoryRepository history;
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
                jdbc.update("DELETE FROM note_history WHERE note_id = UUID_TO_BIN(?)", noteId.toString());
                jdbc.update("DELETE FROM activity_logs WHERE entity_id IN (UUID_TO_BIN(?), UUID_TO_BIN(?))",
                        noteId.toString(), bookId.toString());
                jdbc.update("DELETE FROM notes WHERE notebook_id = UUID_TO_BIN(?)", bookId.toString());
                jdbc.update("DELETE FROM notebook_shares WHERE notebook_id = UUID_TO_BIN(?)", bookId.toString());
                jdbc.update("DELETE FROM notebooks WHERE id = UUID_TO_BIN(?)", bookId.toString());
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
