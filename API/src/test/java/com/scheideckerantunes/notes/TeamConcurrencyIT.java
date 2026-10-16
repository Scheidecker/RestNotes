package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.CreateTeamRequest;
import com.scheideckerantunes.notes.team.TeamConflictException;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent threads invoke real service transactions against one MySQL team aggregate.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@ActiveProfiles("test")
class TeamConcurrencyIT extends MySqlIntegrationSupport {
    private static final UUID ADMIN = UUID.fromString("10000000-0000-4000-8000-000000000001");
    @Autowired
    private TeamService service;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void simultaneousSelfDemotionsCannotRemoveBothAdmins() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UserAccount second = fixture.user();
            service.addMember(fixture.teamId, ADMIN, new AddTeamMemberRequest(second.getEmail(), TeamRole.ADMIN));
            List<Integer> outcomes = race(
                    () -> updateSelf(fixture.teamId, ADMIN),
                    () -> updateSelf(fixture.teamId, second.getId()));
            assertThat(outcomes).containsExactlyInAnyOrder(200, 409);
            assertThat(adminCount(fixture.teamId)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousSelfRemovalsCannotDeleteBothAdmins() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UserAccount second = fixture.user();
            service.addMember(fixture.teamId, ADMIN, new AddTeamMemberRequest(second.getEmail(), TeamRole.ADMIN));
            List<Integer> outcomes = race(
                    () -> removeSelf(fixture.teamId, ADMIN),
                    () -> removeSelf(fixture.teamId, second.getId()));
            assertThat(outcomes).containsExactlyInAnyOrder(204, 409);
            assertThat(adminCount(fixture.teamId)).isEqualTo(1);
        }
    }

    @Test
    void concurrentDuplicateAddsCreateOneMembershipAndOneActivity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            UserAccount target = fixture.user();
            List<Integer> outcomes = race(
                    () -> add(fixture.teamId, target.getEmail()),
                    () -> add(fixture.teamId, target.getEmail()));
            assertThat(outcomes).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id = UUID_TO_BIN(?) "
                    + "AND user_id = UUID_TO_BIN(?)", Integer.class, fixture.teamId.toString(),
                    target.getId().toString())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE team_id = UUID_TO_BIN(?) "
                    + "AND action = 'MEMBER_ADDED'", Integer.class, fixture.teamId.toString())).isEqualTo(1);
        }
    }

    @Test
    void failedEnclosingTransactionRollsBackTeamCreatorMembershipAndActivity() {
        AtomicReference<UUID> createdId = new AtomicReference<>();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            createdId.set(service.create(ADMIN, new CreateTeamRequest("Rollback test", null)).id());
            throw new IllegalStateException("Abort this test transaction");
        })).isInstanceOf(IllegalStateException.class);
        for (String table : List.of("teams", "team_members", "activity_logs")) {
            String column = table.equals("teams") ? "id" : "team_id";
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = UUID_TO_BIN(?)",
                    Integer.class, createdId.get().toString())).isZero();
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

    private int updateSelf(UUID teamId, UUID actorId) {
        try {
            service.updateMember(teamId, actorId, actorId, new UpdateTeamMemberRequest(TeamRole.EDITOR));
            return 200;
        } catch (TeamConflictException exception) {
            return 409;
        }
    }

    private int removeSelf(UUID teamId, UUID actorId) {
        try {
            service.removeMember(teamId, actorId, actorId);
            return 204;
        } catch (TeamConflictException exception) {
            return 409;
        }
    }

    private int add(UUID teamId, String email) {
        try {
            service.addMember(teamId, ADMIN, new AddTeamMemberRequest(email, TeamRole.VIEWER));
            return 201;
        } catch (TeamConflictException exception) {
            return 409;
        }
    }

    private int adminCount(UUID teamId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM team_members WHERE team_id = UUID_TO_BIN(?) "
                + "AND role = 'ADMIN'", Integer.class, teamId.toString());
    }

    /**
     * Only this test's new team/users are cleaned up; seeded teams/users are untouched.
     *
     * @author Carlos Scheidecker
     */
    private final class Fixture implements AutoCloseable {
        private final UUID teamId = service.create(ADMIN, new CreateTeamRequest("Concurrency test", null)).id();
        private final List<UUID> userIds = new ArrayList<>();

        UserAccount user() {
            UserAccount account = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                    users.findById(ADMIN).orElseThrow().getPasswordHash(), "Concurrent admin"));
            userIds.add(account.getId());
            return account;
        }

        @Override
        public void close() {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("DELETE FROM activity_logs WHERE team_id = UUID_TO_BIN(?)", teamId.toString());
                jdbc.update("DELETE FROM team_members WHERE team_id = UUID_TO_BIN(?)", teamId.toString());
                jdbc.update("DELETE FROM teams WHERE id = UUID_TO_BIN(?)", teamId.toString());
                for (UUID userId : userIds) {
                    users.deleteById(userId);
                }
            });
        }
    }
}
