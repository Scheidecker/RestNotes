package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scheideckerantunes.notes.auth.TokenService;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.note.AddTagRequest;
import com.scheideckerantunes.notes.note.NoteEtags;
import com.scheideckerantunes.notes.note.NoteLockService;
import com.scheideckerantunes.notes.note.NoteLockStore;
import com.scheideckerantunes.notes.note.NoteLockTokens;
import com.scheideckerantunes.notes.note.NoteOperationException;
import com.scheideckerantunes.notes.note.NoteService;
import com.scheideckerantunes.notes.note.ReplaceNoteRequest;
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
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Lease HTTP requests exercise real authorization and shared JPA/JDBC transactions.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional(isolation = Isolation.READ_COMMITTED)
class NoteLockingIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = id(1, 1);
    private static final UUID RECIPIENT = id(1, 2);
    private static final UUID BOOK = id(3, 1);
    private static final UUID NOTE = id(4, 1);
    private static final UUID OTHER_TEAM = id(2, 2);
    private static final String RESOURCE = "/api/v1/notes/" + NOTE;
    private static final String LOCK = RESOURCE + "/lock";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
    @Autowired
    private NoteService notes;
    @Autowired
    private NoteLockService locks;
    @Autowired
    private NoteLockStore store;
    @Autowired
    private NotebookService notebooks;
    @Autowired
    private TeamService teams;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private NoteHistoryRepository history;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    @Test
    void lifecycleDoesNotChangeNoteEtagHistoryAttributionOrActivity() throws Exception {
        var original = notes.detail(NOTE, OWNER);
        String etag = tag(OWNER);
        int activity = activityCount();
        mvc.perform(as(get(LOCK), OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        var acquired = mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", etag)).andReturn().getResponse();
        String token = json.readTree(acquired.getContentAsString()).get("token").asString();
        var lease = store.find(NOTE).orElseThrow();
        assertThat(Duration.between(lease.acquiredAt(), lease.expiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(NoteLockTokens.matches(token, lease.digest())).isTrue();
        assertThat(lease.digest().equals(token)).isFalse();
        mvc.perform(as(get(LOCK), OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.ownerId").value(OWNER.toString()))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.digest").doesNotExist());
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("ETag", etag));
        assertThat(store.find(NOTE).orElseThrow().expiresAt()).isAfterOrEqualTo(lease.expiresAt());
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, token))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", etag));
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", etag)).andExpect(status().isNoContent());
        assertThat(store.find(NOTE)).isEmpty();
        assertThat(notes.detail(NOTE, OWNER)).isEqualTo(original);
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(1);
        assertThat(activityCount()).isEqualTo(activity);
    }

    @Test
    void everyMutationIncludingNoOpsRequiresOwnerTokenAndThenPreservesNormalBehavior() throws Exception {
        String token = acquire(OWNER);
        String etag = tag(OWNER);
        var original = notes.detail(NOTE, OWNER);
        var blocked = List.of(
                put(RESOURCE).content(json.writeValueAsString(new ReplaceNoteRequest(
                        original.title(), original.content(), original.pinned()))),
                patch(RESOURCE).content("{\"pinned\":true}"),
                delete(RESOURCE),
                post(RESOURCE + "/tags").content("{\"name\":\"lease-test\"}"),
                delete(RESOURCE + "/tags/absent"));
        for (var request : blocked) {
            mvc.perform(as(request, OWNER).header("If-Match", etag)).andExpect(status().isLocked())
                    .andExpect(jsonPath("$.code").value("NOTE_LOCKED"));
        }
        assertThatThrownBy(() -> notes.addTag(NOTE, OWNER, etag, new AddTagRequest("service-bypass")))
                .isInstanceOf(NoteOperationException.class).hasMessage("NOTE_LOCKED");
        mvc.perform(as(put(RESOURCE), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, token)
                        .content(json.writeValueAsString(new ReplaceNoteRequest(
                                original.title(), original.content(), original.pinned()))))
                .andExpect(status().isOk()).andExpect(header().string("ETag", etag));
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, token)
                        .content("{\"content\":\"Lease holder update\"}")).andExpect(status().isOk());
        mvc.perform(as(post(RESOURCE + "/tags"), OWNER).header("If-Match", tag(OWNER))
                        .header(NoteLockTokens.HEADER, token).content("{\"name\":\"lease-test\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post(RESOURCE + "/tags"), OWNER).header("If-Match", tag(OWNER))
                        .content("{\"name\":\"lease-test\"}")).andExpect(status().isLocked());
        mvc.perform(as(delete(RESOURCE + "/tags/lease-test"), OWNER).header("If-Match", tag(OWNER))
                        .header(NoteLockTokens.HEADER, token)).andExpect(status().isNoContent());
        mvc.perform(as(delete(RESOURCE), OWNER).header("If-Match", tag(OWNER)).header(NoteLockTokens.HEADER, token))
                .andExpect(status().isNoContent());
        assertThat(store.find(NOTE)).isEmpty();
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(4);
        mvc.perform(as(get(LOCK), OWNER)).andExpect(status().isNotFound());
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag)).andExpect(status().isNotFound());
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", etag)).andExpect(status().isNotFound());
    }

    @Test
    void sameUserInAnotherClientCannotReacquireOrReleaseAndTokensAreNoteScoped() throws Exception {
        String token = acquire(OWNER);
        String etag = tag(OWNER);
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag)).andExpect(status().isLocked());
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", etag)).andExpect(status().isLocked());
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag)
                        .header(NoteLockTokens.HEADER, NoteLockTokens.generate()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_LOCK_TOKEN"));
        UUID otherNote = id(4, 2);
        mvc.perform(as(post("/api/v1/notes/" + otherNote + "/lock"), RECIPIENT)
                        .header("If-Match", NoteEtags.from(notes.detail(otherNote, RECIPIENT), RECIPIENT))
                        .header(NoteLockTokens.HEADER, token)).andExpect(status().isConflict());
    }

    @Test
    void expiredTokenCannotRenewWriteOrReleaseAndTakeoverFencesOldGeneration() throws Exception {
        String old = acquire(OWNER);
        expire();
        String etag = tag(OWNER);
        mvc.perform(as(get(LOCK), OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.ownerId").isEmpty());
        for (var request : List.of(post(LOCK), delete(LOCK),
                patch(RESOURCE).content("{\"pinned\":true}"))) {
            mvc.perform(as(request, OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, old))
                    .andExpect(status().isConflict());
        }
        String replacement = acquire(OWNER);
        assertThat(old.equals(replacement)).isFalse();
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, old))
                .andExpect(status().isConflict());
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", etag).header(NoteLockTokens.HEADER, replacement))
                .andExpect(status().isOk());
        expire();
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", etag).content("{\"pinned\":true}"))
                .andExpect(status().isOk());
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", tag(OWNER))).andExpect(status().isNoContent());
        assertThat(store.find(NOTE)).isEmpty();
    }

    @Test
    void authenticationPreconditionsAndMalformedTokensHaveSanitizedOutcomes() throws Exception {
        for (var request : List.of(get(LOCK), post(LOCK), delete(LOCK))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(as(get(LOCK), RECIPIENT)).andExpect(status().isNotFound());
        mvc.perform(as(post(LOCK), RECIPIENT)).andExpect(status().isNotFound());
        mvc.perform(as(post(LOCK), OWNER)).andExpect(status().isPreconditionRequired());
        mvc.perform(as(delete(LOCK), OWNER)).andExpect(status().isPreconditionRequired());
        for (String invalid : List.of("*", "W/" + tag(OWNER), "\"one\",\"two\"")) {
            mvc.perform(as(post(LOCK), OWNER).header("If-Match", invalid)).andExpect(status().isBadRequest());
        }
        String old = tag(OWNER);
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", old).content("{\"pinned\":true}"))
                .andExpect(status().isOk());
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", old)).andExpect(status().isPreconditionFailed());
        for (String malformed : List.of("", "invalid", "a".repeat(43), "a".repeat(44))) {
            mvc.perform(as(post(LOCK), OWNER).header("If-Match", tag(OWNER))
                            .header(NoteLockTokens.HEADER, malformed)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_LOCK_TOKEN"));
        }
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", tag(OWNER))
                        .header(NoteLockTokens.HEADER, NoteLockTokens.generate(), NoteLockTokens.generate()))
                .andExpect(status().isBadRequest());
        assertThat(store.find(NOTE)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("roles")
    void cappedSharedRolesCanReadMetadataButOnlyEditorsAcquire(ShareAccessLevel grant, TeamRole role)
            throws Exception {
        var user = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(OWNER).orElseThrow().getPasswordHash(), "Lease collaborator"));
        teams.addMember(OTHER_TEAM, RECIPIENT, new AddTeamMemberRequest(user.getEmail(), role));
        share(grant);
        String token = acquire(OWNER);
        boolean editor = grant == ShareAccessLevel.EDITOR && role != TeamRole.VIEWER;
        mvc.perform(as(get(LOCK), user.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true)).andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(as(post(LOCK), user.getId()).header("If-Match", tag(user.getId())))
                .andExpect(status().is(editor ? 423 : 403));
        locks.release(NOTE, OWNER, tag(OWNER), token);
        mvc.perform(as(post(LOCK), user.getId()).header("If-Match", tag(user.getId())))
                .andExpect(status().is(editor ? 200 : 403));
    }

    @Test
    void sharedEditorOwnsLeaseButNeitherOwnerAdminNorStolenTokenCanBypassIt() throws Exception {
        share(ShareAccessLevel.EDITOR);
        String token = acquire(RECIPIENT);
        mvc.perform(as(delete(LOCK), OWNER).header("If-Match", tag(OWNER)).header(NoteLockTokens.HEADER, token))
                .andExpect(status().isLocked());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", tag(OWNER)).header(NoteLockTokens.HEADER, token)
                        .content("{\"pinned\":true}")).andExpect(status().isLocked());
        mvc.perform(as(patch(RESOURCE), RECIPIENT).header("If-Match", tag(RECIPIENT))
                        .header(NoteLockTokens.HEADER, token).content("{\"pinned\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessLevel").value("EDITOR"));
        notebooks.revoke(BOOK, OWNER, OTHER_TEAM, bookTag());
        for (var request : List.of(get(LOCK), post(LOCK), delete(LOCK))) {
            mvc.perform(as(request, RECIPIENT).header("If-Match", "old")
                    .header(NoteLockTokens.HEADER, token)).andExpect(status().isNotFound());
        }
        mvc.perform(as(post(LOCK), OWNER).header("If-Match", tag(OWNER))).andExpect(status().isLocked());
        expire();
        acquire(OWNER);
    }

    @Test
    void membershipDowngradeStopsHolderAndNotebookArchiveHidesLease() throws Exception {
        var user = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(OWNER).orElseThrow().getPasswordHash(), "Lease editor"));
        teams.addMember(OTHER_TEAM, RECIPIENT, new AddTeamMemberRequest(user.getEmail(), TeamRole.EDITOR));
        share(ShareAccessLevel.EDITOR);
        String token = acquire(user.getId());
        teams.updateMember(OTHER_TEAM, RECIPIENT, user.getId(), new UpdateTeamMemberRequest(TeamRole.VIEWER));
        mvc.perform(as(post(LOCK), user.getId()).header("If-Match", tag(user.getId()))
                        .header(NoteLockTokens.HEADER, token)).andExpect(status().isForbidden());
        mvc.perform(as(delete(LOCK), user.getId()).header("If-Match", tag(user.getId()))
                        .header(NoteLockTokens.HEADER, token)).andExpect(status().isForbidden());
        notebooks.archive(BOOK, OWNER, bookTag());
        for (var request : List.of(get(LOCK), post(LOCK), delete(LOCK))) {
            mvc.perform(as(request, OWNER).header("If-Match", "old")).andExpect(status().isNotFound());
        }
        assertThat(store.find(NOTE)).isPresent();
    }

    private String acquire(UUID actor) throws Exception {
        var response = mvc.perform(as(post(LOCK), actor).header("If-Match", tag(actor)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("token").asString();
    }

    private void expire() {
        jdbc.update("UPDATE note_locks SET acquired_at = UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE, "
                + "expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE note_id = UUID_TO_BIN(?)", NOTE.toString());
    }

    private int activityCount() {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE entity_id = UUID_TO_BIN(?)",
                Integer.class, NOTE.toString());
    }

    private void share(ShareAccessLevel access) {
        notebooks.share(BOOK, OWNER, bookTag(), new NotebookShareRequest(OTHER_TEAM, access));
    }

    private String tag(UUID actor) {
        return NoteEtags.from(notes.detail(NOTE, actor), actor);
    }

    private String bookTag() {
        return NotebookEtags.value(BOOK, notebooks.detail(BOOK, OWNER).version(), OWNER, TeamRole.ADMIN);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.issue(actor).accessToken());
    }

    private static Stream<Arguments> roles() {
        return Arrays.stream(ShareAccessLevel.values()).flatMap(grant ->
                Arrays.stream(TeamRole.values()).map(role -> Arguments.of(grant, role)));
    }

    private static UUID id(int type, int index) {
        return UUID.fromString(type + "0000000-0000-4000-8000-00000000000" + index);
    }
}
