package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.auth.TokenService;
import com.scheideckerantunes.notes.note.NoteRepository;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookRepository;
import com.scheideckerantunes.notes.notebook.NotebookShareRepository;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.CreateTeamRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.team.UpdateTeamMemberRequest;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Sharing security and notebook writes use real JWTs, schema constraints and MySQL transactions.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class NotebookSharingIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = id(1, 1);
    private static final UUID RECIPIENT = id(1, 2);
    private static final UUID OWNER_TEAM = id(2, 1);
    private static final UUID RECIPIENT_TEAM = id(2, 2);
    private static final UUID BOOK = id(3, 1);
    private static final UUID NOTE = id(4, 1);
    private static final String BASE = "/api/v1/notebooks";
    private static final String RESOURCE = BASE + "/" + BOOK;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
    @Autowired
    private NotebookRepository notebooks;
    @Autowired
    private NotebookShareRepository shares;
    @Autowired
    private NoteRepository notes;
    @Autowired
    private TeamService teams;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private ActivityLogRepository activity;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ownerAdminCreatesListsAndConditionallyUpdatesMetadata() throws Exception {
        var created = mvc.perform(as(post(BASE), OWNER).content(body(Map.of(
                        "teamId", OWNER_TEAM, "title", " New notebook ", "description", " Initial "))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("New notebook"))
                .andExpect(jsonPath("$.accessLevel").value("ADMIN"))
                .andExpect(header().exists("ETag")).andReturn().getResponse();
        UUID createdId = UUID.fromString(json.readTree(created.getContentAsString()).path("id").asText());
        assertThat(created.getHeader("Location")).isEqualTo(BASE + "/" + createdId);
        mvc.perform(as(get(BASE).param("size", "1"), OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(createdId.toString()));
        mvc.perform(as(get(BASE).param("teamId", RECIPIENT_TEAM.toString()), OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        var updated = mvc.perform(as(patch(BASE + "/" + createdId), OWNER)
                        .header("If-Match", created.getHeader("ETag")).content(body(Map.of("description", ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse();
        mvc.perform(as(patch(BASE + "/" + createdId), OWNER).header("If-Match", updated.getHeader("ETag"))
                        .content(body(Map.of("description", ""))))
                .andExpect(status().isOk()).andExpect(header().string("ETag", updated.getHeader("ETag")));
        mvc.perform(as(get(BASE + "/" + createdId), RECIPIENT)).andExpect(status().isNotFound());
    }

    @Test
    void sharingUpdatesVisibilityAndVersionsAndRevocationIsImmediate() throws Exception {
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isNotFound());
        String before = etag(OWNER);
        String first = share(RECIPIENT_TEAM, ShareAccessLevel.VIEWER, before);
        assertThat(first).isNotEqualTo(before);
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("VIEWER"));
        assertThat(notes.findVisibleById(NOTE, RECIPIENT)).isPresent();
        mvc.perform(as(get(BASE).param("size", "1"), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(get(RESOURCE + "/permissions").param("size", "1"), OWNER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        long count = activityCount();
        assertThat(share(RECIPIENT_TEAM, ShareAccessLevel.VIEWER, first)).isEqualTo(first);
        assertThat(activityCount()).isEqualTo(count);
        String second = share(RECIPIENT_TEAM, ShareAccessLevel.EDITOR, first);
        assertThat(second).isNotEqualTo(first);
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("EDITOR"));
        var revoked = mvc.perform(as(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM), OWNER)
                        .header("If-Match", second))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(revoked.getHeader("ETag")).isNotEqualTo(second);
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isNotFound());
        assertThat(notes.findVisibleById(NOTE, RECIPIENT)).isEmpty();
        mvc.perform(as(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM), OWNER)
                        .header("If-Match", revoked.getHeader("ETag")))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", revoked.getHeader("ETag")));
    }

    @ParameterizedTest
    @EnumSource(TeamRole.class)
    void recipientRolesCapEditorSharesAndNeverGrantAdministration(TeamRole role) throws Exception {
        UserAccount member = user();
        teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), role));
        share(RECIPIENT_TEAM, ShareAccessLevel.EDITOR, etag(OWNER));
        String expected = role == TeamRole.VIEWER ? "VIEWER" : "EDITOR";
        var response = mvc.perform(as(get(RESOURCE), member.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value(expected)).andReturn().getResponse();
        String tag = response.getHeader("ETag");
        mvc.perform(as(patch(RESOURCE), member.getId()).header("If-Match", tag)
                .content(body(Map.of("title", "Forbidden")))).andExpect(status().isForbidden());
        mvc.perform(as(delete(RESOURCE), member.getId()).header("If-Match", tag)).andExpect(status().isForbidden());
        mvc.perform(as(get(RESOURCE + "/permissions"), member.getId())).andExpect(status().isForbidden());
        mvc.perform(as(patch(RESOURCE + "/permissions"), member.getId()).header("If-Match", tag)
                .content(body(Map.of("teamId", RECIPIENT_TEAM, "accessLevel", "VIEWER"))))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/teams/" + OWNER_TEAM), member.getId())).andExpect(status().isNotFound());
        assertThat(notes.findVisibleById(NOTE, member.getId())).isPresent();
    }

    @Test
    void multipleSharedPathsUseStrongestRoleWithoutDuplicateRowsOrCounts() throws Exception {
        UUID additionalTeam = teams.create(RECIPIENT, new CreateTeamRequest("Extra recipient path", null)).id();
        share(RECIPIENT_TEAM, ShareAccessLevel.EDITOR, etag(OWNER));
        share(additionalTeam, ShareAccessLevel.VIEWER, etag(OWNER));
        mvc.perform(as(get(BASE), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("EDITOR"));
        mvc.perform(as(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM), OWNER).header("If-Match", etag(OWNER)))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("VIEWER"));
        assertThat(notes.findVisibleTo(RECIPIENT, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(2);
        mvc.perform(as(delete(RESOURCE + "/permissions/" + additionalTeam), OWNER).header("If-Match", etag(OWNER)))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isNotFound());
    }

    @Test
    void recipientDowngradeChangesTheRepresentationEtagAndRemovalRevokesAccess() throws Exception {
        UserAccount member = user();
        teams.addMember(RECIPIENT_TEAM, RECIPIENT,
                new AddTeamMemberRequest(member.getEmail(), TeamRole.EDITOR));
        share(RECIPIENT_TEAM, ShareAccessLevel.EDITOR, etag(OWNER));
        String oldTag = etag(member.getId());
        long version = notebooks.findById(BOOK).orElseThrow().getVersion();
        teams.updateMember(RECIPIENT_TEAM, RECIPIENT, member.getId(), new UpdateTeamMemberRequest(TeamRole.VIEWER));
        var response = mvc.perform(as(get(RESOURCE), member.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("VIEWER")).andReturn().getResponse();
        assertThat(response.getHeader("ETag")).isNotEqualTo(oldTag);
        assertThat(notebooks.findById(BOOK).orElseThrow().getVersion()).isEqualTo(version);
        teams.removeMember(RECIPIENT_TEAM, RECIPIENT, member.getId());
        mvc.perform(as(get(RESOURCE), member.getId())).andExpect(status().isNotFound());
        assertThat(notes.findVisibleById(NOTE, member.getId())).isEmpty();
    }

    @Test
    void ownerViewerCanUseAnEditorShareButCannotAdministerOrCreateNotebooks() throws Exception {
        UserAccount member = user();
        teams.addMember(OWNER_TEAM, OWNER, new AddTeamMemberRequest(member.getEmail(), TeamRole.VIEWER));
        teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), TeamRole.EDITOR));
        share(RECIPIENT_TEAM, ShareAccessLevel.EDITOR, etag(OWNER));
        mvc.perform(as(get(RESOURCE), member.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("EDITOR"));
        mvc.perform(as(patch(RESOURCE), member.getId()).header("If-Match", etag(member.getId()))
                .content(body(Map.of("title", "Denied")))).andExpect(status().isForbidden());
        mvc.perform(as(post(BASE), member.getId()).content(body(Map.of("teamId", OWNER_TEAM, "title", "Denied"))))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(BASE), OWNER).content(body(Map.of("teamId", RECIPIENT_TEAM, "title", "Hidden"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerDemotionImmediatelyRejectsOldAdminTokensForNotebookWrites() throws Exception {
        UserAccount successor = user();
        teams.addMember(OWNER_TEAM, OWNER, new AddTeamMemberRequest(successor.getEmail(), TeamRole.ADMIN));
        String old = etag(OWNER);
        String oldToken = tokens.issue(OWNER).accessToken();
        teams.updateMember(OWNER_TEAM, successor.getId(), OWNER, new UpdateTeamMemberRequest(TeamRole.VIEWER));
        mvc.perform(get(RESOURCE).header("Authorization", "Bearer " + oldToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("VIEWER"));
        mvc.perform(patch(RESOURCE).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + oldToken).header("If-Match", old)
                .content(body(Map.of("title", "Denied"))))
                .andExpect(status().isForbidden());
        mvc.perform(patch(RESOURCE + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + oldToken).header("If-Match", old)
                .content(body(Map.of("teamId", RECIPIENT_TEAM, "accessLevel", "EDITOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void archiveHidesAllPathsWithoutDeletingNotesHistoryOrGrants() throws Exception {
        share(RECIPIENT_TEAM, ShareAccessLevel.VIEWER, etag(OWNER));
        var archived = mvc.perform(as(delete(RESOURCE), OWNER).header("If-Match", etag(OWNER)))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        for (UUID actor : List.of(OWNER, RECIPIENT)) {
            mvc.perform(as(get(RESOURCE), actor)).andExpect(status().isNotFound());
            assertThat(notes.findVisibleById(NOTE, actor)).isEmpty();
        }
        mvc.perform(as(get(RESOURCE + "/permissions"), OWNER)).andExpect(status().isNotFound());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", archived.getHeader("ETag"))
                .content(body(Map.of("title", "Hidden")))).andExpect(status().isNotFound());
        long count = activityCount();
        mvc.perform(as(delete(RESOURCE), OWNER).header("If-Match", archived.getHeader("ETag")))
                .andExpect(status().isNoContent());
        assertThat(activityCount()).isEqualTo(count);
        assertThat(notebooks.existsById(BOOK)).isTrue();
        assertThat(notes.existsById(NOTE)).isTrue();
        assertThat(shares.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_history WHERE note_id = UUID_TO_BIN(?)",
                Integer.class, NOTE.toString())).isEqualTo(1);
    }

    @Test
    void everyMutationRequiresCurrentSingleStrongActorScopedPreconditions() throws Exception {
        String old = etag(OWNER);
        mvc.perform(as(patch(RESOURCE), OWNER).content(body(Map.of("title", "Change"))))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(delete(RESOURCE), OWNER)).andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER)
                .content(body(Map.of("teamId", RECIPIENT_TEAM, "accessLevel", "VIEWER"))))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM), OWNER))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", old).content(body(Map.of("title", "Changed"))))
                .andExpect(status().isOk());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", old).content(body(Map.of("title", "Stale"))))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER).header("If-Match", old)
                .content(body(Map.of("teamId", RECIPIENT_TEAM, "accessLevel", "VIEWER"))))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM), OWNER).header("If-Match", old))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete(RESOURCE), OWNER).header("If-Match", old))
                .andExpect(status().isPreconditionFailed());
        for (String invalid : List.of("*", "W/" + old, old + "," + old, "invalid")) {
            mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", invalid)
                    .content(body(Map.of("title", "Bad header")))).andExpect(status().isBadRequest());
        }
        String otherActor = NotebookEtags.value(BOOK, 1, RECIPIENT, TeamRole.ADMIN);
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", otherActor)
                .content(body(Map.of("title", "Bad actor")))).andExpect(status().isPreconditionFailed());
    }

    @Test
    void rejectsSelfSharingUnknownTargetsAndInvalidInputs() throws Exception {
        mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER).header("If-Match", etag(OWNER))
                .content(body(Map.of("teamId", OWNER_TEAM, "accessLevel", "VIEWER"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_TEAM_SHARE"));
        mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER).header("If-Match", etag(OWNER))
                .content(body(Map.of("teamId", UUID.randomUUID(), "accessLevel", "VIEWER"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER).header("If-Match", etag(OWNER))
                .content(body(Map.of("teamId", RECIPIENT_TEAM, "accessLevel", "ADMIN"))))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", etag(OWNER)).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_PATCH"));
        for (Map<String, Object> input : List.<Map<String, Object>>of(
                Map.of("teamId", OWNER_TEAM, "title", " "),
                Map.of("teamId", OWNER_TEAM, "title", "n".repeat(151)),
                Map.of("teamId", OWNER_TEAM, "title", "Valid", "description", "d".repeat(2001)),
                Map.of("teamId", OWNER_TEAM, "title", "Valid", "createdBy", RECIPIENT))) {
            mvc.perform(as(post(BASE), OWNER).content(body(input))).andExpect(status().isBadRequest());
        }
        for (String path : List.of(BASE, RESOURCE + "/permissions")) {
            mvc.perform(as(get(path + "?size=101"), OWNER)).andExpect(status().isBadRequest());
            mvc.perform(as(get(path + "?page=-1"), OWNER)).andExpect(status().isBadRequest());
        }
        assertThat(shares.count()).isZero();
    }

    @Test
    void shareConstraintsRejectAdminLevelsDuplicatesAndDanglingTargets() throws Exception {
        share(RECIPIENT_TEAM, ShareAccessLevel.VIEWER, etag(OWNER));
        assertThatThrownBy(() -> jdbc.update("UPDATE notebook_shares SET access_level = 'ADMIN'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notebook_shares SELECT * FROM notebook_shares"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notebook_shares "
                + "VALUES(UUID_TO_BIN(?), UUID_TO_BIN(?), 'VIEWER', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                BOOK.toString(), UUID.randomUUID().toString())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void notebookRoutesRequireBearerAuthentication() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(RESOURCE)).andExpect(status().isUnauthorized());
        mvc.perform(get(RESOURCE + "/permissions")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(RESOURCE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(RESOURCE)).andExpect(status().isUnauthorized());
        mvc.perform(patch(RESOURCE + "/permissions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(RESOURCE + "/permissions/" + RECIPIENT_TEAM)).andExpect(status().isUnauthorized());
    }

    private String share(UUID target, ShareAccessLevel level, String etag) throws Exception {
        return mvc.perform(as(patch(RESOURCE + "/permissions"), OWNER).header("If-Match", etag)
                        .content(body(Map.of("teamId", target, "accessLevel", level))))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private String etag(UUID actor) throws Exception {
        return mvc.perform(as(get(RESOURCE), actor)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    private UserAccount user() {
        return users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(OWNER).orElseThrow().getPasswordHash(), "Notebook recipient"));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.issue(actor).accessToken());
    }

    private String body(Object value) {
        return json.writeValueAsString(value);
    }

    private long activityCount() {
        return activity.findByTeamId(OWNER_TEAM, PageRequest.of(0, 100)).getTotalElements();
    }

    private static UUID id(int type, int index) {
        return UUID.fromString(type + "0000000-0000-4000-8000-00000000000" + index);
    }
}
