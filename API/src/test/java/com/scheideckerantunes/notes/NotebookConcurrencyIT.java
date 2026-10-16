package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.common.ResourceNotFoundException;
import com.scheideckerantunes.notes.notebook.CreateNotebookRequest;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookOperationException;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.notebook.NotebookService;
import com.scheideckerantunes.notes.notebook.NotebookShareRepository;
import com.scheideckerantunes.notes.notebook.NotebookShareRequest;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.notebook.UpdateNotebookRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Separate MySQL transactions prove metadata and grants share one aggregate version.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@ActiveProfiles("test")
class NotebookConcurrencyIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID RECIPIENT = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_TEAM = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RECIPIENT_TEAM = UUID.fromString("20000000-0000-4000-8000-000000000002");
    @Autowired
    private NotebookService service;
    @Autowired
    private NotebookRepository notebooks;
    @Autowired
    private NotebookShareRepository shares;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void concurrentMetadataUpdatesCannotOverwriteTheSameVersion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.etag();
            assertThat(race(
                    () -> result(() -> service.update(fixture.id, OWNER, etag,
                            new UpdateNotebookRequest("First", null))),
                    () -> result(() -> service.update(fixture.id, OWNER, etag,
                            new UpdateNotebookRequest("Second", null)))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(notebooks.findById(fixture.id).orElseThrow().getVersion()).isEqualTo(1);
        }
    }

    @Test
    void reciprocalCrossTeamSharesUseOneTeamLockOrder() throws Exception {
        try (Fixture first = new Fixture(); Fixture second = new Fixture(RECIPIENT, RECIPIENT_TEAM)) {
            String firstTag = first.etag();
            String secondTag = second.etag();
            assertThat(race(
                    () -> result(() -> service.share(first.id, OWNER, firstTag,
                            new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.VIEWER))),
                    () -> result(() -> service.share(second.id, RECIPIENT, secondTag,
                            new NotebookShareRequest(OWNER_TEAM, ShareAccessLevel.VIEWER)))))
                    .containsExactlyInAnyOrder(200, 200);
            assertThat(service.detail(first.id, RECIPIENT).accessLevel()).isEqualTo(TeamRole.VIEWER);
            assertThat(service.detail(second.id, OWNER).accessLevel()).isEqualTo(TeamRole.VIEWER);
        }
    }

    @Test
    void archivalAndNewSharingCannotBothSucceedAgainstOneVersion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.etag();
            var outcomes = race(
                    () -> result(() -> service.archive(fixture.id, OWNER, etag)),
                    () -> result(() -> service.share(fixture.id, OWNER, etag,
                            new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.VIEWER))));
            boolean archived = notebooks.findById(fixture.id).orElseThrow().getArchivedAt() != null;
            assertThat(outcomes).containsExactlyInAnyOrder(200, archived ? 404 : 412);
            assertThat(notebooks.findById(fixture.id).orElseThrow().getVersion()).isEqualTo(1);
            if (archived) {
                assertThatThrownBy(() -> service.detail(fixture.id, RECIPIENT))
                        .isInstanceOf(ResourceNotFoundException.class);
            }
        }
    }

    @Test
    void competingGrantUpsertsUseTheNotebookVersionWithoutDuplicates() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.etag();
            assertThat(race(
                    () -> result(() -> service.share(fixture.id, OWNER, etag,
                            new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.VIEWER))),
                    () -> result(() -> service.share(fixture.id, OWNER, etag,
                            new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR)))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notebook_shares WHERE notebook_id = UUID_TO_BIN(?)",
                    Integer.class, fixture.id.toString())).isEqualTo(1);
            assertThat(notebooks.findById(fixture.id).orElseThrow().getVersion()).isEqualTo(1);
        }
    }

    @Test
    void revocationAndGrantChangesCannotBothCommitAgainstOneVersion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            service.share(fixture.id, OWNER, fixture.etag(),
                    new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.VIEWER));
            String etag = fixture.etag();
            assertThat(race(
                    () -> result(() -> service.revoke(fixture.id, OWNER, RECIPIENT_TEAM, etag)),
                    () -> result(() -> service.share(fixture.id, OWNER, etag,
                            new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR)))))
                    .containsExactlyInAnyOrder(200, 412);
            assertThat(notebooks.findById(fixture.id).orElseThrow().getVersion()).isEqualTo(2);
        }
    }

    @Test
    void failedShareTransactionRollsBackGrantVersionAndActivityTogether() {
        try (Fixture fixture = new Fixture()) {
            String etag = fixture.etag();
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.share(fixture.id, OWNER, etag,
                        new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR));
                throw new IllegalStateException("Abort this verification transaction");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(shares.count()).isZero();
            assertThat(notebooks.findById(fixture.id).orElseThrow().getVersion()).isZero();
            assertThatThrownBy(() -> service.detail(fixture.id, RECIPIENT))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs "
                    + "WHERE entity_id = UUID_TO_BIN(?) AND action = 'NOTEBOOK_SHARED'",
                    Integer.class, fixture.id.toString())).isZero();
        }
    }

    private int result(Callable<?> operation) throws Exception {
        try {
            operation.call();
            return 200;
        } catch (NotebookOperationException exception) {
            return exception.getCode().status().value();
        } catch (ResourceNotFoundException exception) {
            return 404;
        }
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

    /**
     * Cleans up only newly created notebooks in the disposable test database.
     *
     * @author Carlos Scheidecker
     */
    private final class Fixture implements AutoCloseable {
        private final UUID id;
        private final UUID owner;

        Fixture() {
            this(OWNER, OWNER_TEAM);
        }

        Fixture(UUID owner, UUID ownerTeam) {
            this.owner = owner;
            id = service.create(owner, new CreateNotebookRequest(ownerTeam, "Concurrent notebook", null)).id();
        }

        String etag() {
            return NotebookEtags.value(id, service.detail(id, owner).version(), owner, TeamRole.ADMIN);
        }

        @Override
        public void close() {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM notebook_shares WHERE notebook_id = UUID_TO_BIN(?)", id.toString());
                jdbc.update("DELETE FROM activity_logs WHERE entity_type = 'notebook' AND entity_id = UUID_TO_BIN(?)",
                        id.toString());
                jdbc.update("DELETE FROM notebooks WHERE id = UUID_TO_BIN(?)", id.toString());
            });
        }
    }
}
