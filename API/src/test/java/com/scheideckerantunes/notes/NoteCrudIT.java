package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.scheideckerantunes.notes.note.NoteEtags;
import com.scheideckerantunes.notes.note.NoteLimits;
import com.scheideckerantunes.notes.note.NoteRepository;
import com.scheideckerantunes.notes.note.NoteTag;
import com.scheideckerantunes.notes.note.NoteTagRepository;
import com.scheideckerantunes.notes.note.Tag;
import com.scheideckerantunes.notes.note.TagRepository;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookService;
import com.scheideckerantunes.notes.notebook.NotebookShareRequest;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.notebook.UpdateNotebookRequest;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Note HTTP workflows use real JWTs/MySQL and roll back every fixture, without content logging.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional(isolation = Isolation.READ_COMMITTED)
class NoteCrudIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = id(1, 1);
    private static final UUID RECIPIENT = id(1, 2);
    private static final UUID OWNER_TEAM = id(2, 1);
    private static final UUID RECIPIENT_TEAM = id(2, 2);
    private static final UUID BOOK = id(3, 1);
    private static final UUID NOTE = id(4, 1);
    private static final String COLLECTION = "/api/v1/notebooks/" + BOOK + "/notes";
    private static final String RESOURCE = "/api/v1/notes/" + NOTE;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
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
    private TagRepository tags;
    @Autowired
    private NoteTagRepository noteTags;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsReadsReplacesPatchesAndArchivesWithoutLosingHistory() throws Exception {
        var created = mvc.perform(as(post(COLLECTION), OWNER)
                        .content(body(Map.of("title", " New note ", "content", "# Markdown\n\nBody"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("New note"))
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.createdBy").value(OWNER.toString())).andReturn().getResponse();
        UUID noteId = UUID.fromString(json.readTree(created.getContentAsString()).path("id").asText());
        String path = "/api/v1/notes/" + noteId;
        assertThat(created.getHeader("Location")).isEqualTo(path);
        assertThat(history.findLatestVersion(noteId)).isEqualTo(1);
        mvc.perform(as(get(path), OWNER)).andExpect(status().isOk())
                .andExpect(header().string("ETag", created.getHeader("ETag")));
        mvc.perform(as(get(COLLECTION).param("size", "1"), OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(noteId.toString()))
                .andExpect(jsonPath("$.items[0].content").doesNotExist());
        var replaced = mvc.perform(as(put(path), OWNER).header("If-Match", created.getHeader("ETag"))
                        .content(body(Map.of("title", "Replaced", "content", "Replacement", "pinned", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andReturn().getResponse();
        var patched = mvc.perform(as(patch(path), OWNER).header("If-Match", replaced.getHeader("ETag"))
                        .content(body(Map.of("content", ""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Replaced"))
                .andExpect(jsonPath("$.pinned").value(true)).andExpect(jsonPath("$.content").value(""))
                .andReturn().getResponse();
        assertThat(history.findLatestVersion(noteId)).isEqualTo(3);
        long activity = activityCount(noteId);
        mvc.perform(as(put(path), OWNER).header("If-Match", patched.getHeader("ETag"))
                        .content(body(Map.of("title", "Replaced", "content", "", "pinned", true))))
                .andExpect(status().isOk()).andExpect(header().string("ETag", patched.getHeader("ETag")));
        assertThat(history.findLatestVersion(noteId)).isEqualTo(3);
        assertThat(activityCount(noteId)).isEqualTo(activity);
        var archived = mvc.perform(as(delete(path), OWNER).header("If-Match", patched.getHeader("ETag")))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        mvc.perform(as(get(path), OWNER)).andExpect(status().isNotFound());
        mvc.perform(as(delete(path), OWNER).header("If-Match", archived.getHeader("ETag")))
                .andExpect(status().isNoContent());
        assertThat(history.findLatestVersion(noteId)).isEqualTo(3);
        assertThat(activityCount(noteId)).isEqualTo(activity + 1);
        assertThat(notes.findById(noteId).orElseThrow().getArchivedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(TeamRole.class)
    void owningTeamRolesGovernReadAndWrite(TeamRole role) throws Exception {
        UserAccount member = user();
        teams.addMember(OWNER_TEAM, OWNER, new AddTeamMemberRequest(member.getEmail(), role));
        String tag = noteTag(NOTE, member.getId());
        mvc.perform(as(get(COLLECTION), member.getId())).andExpect(status().isOk());
        int expected = role == TeamRole.VIEWER ? 403 : 200;
        mvc.perform(as(patch(RESOURCE), member.getId()).header("If-Match", tag).content(body(Map.of("pinned", true))))
                .andExpect(status().is(expected));
        mvc.perform(as(post(COLLECTION), member.getId()).content(body(Map.of("title", "Created", "content", ""))))
                .andExpect(status().is(role == TeamRole.VIEWER ? 403 : 201));
        if (role != TeamRole.VIEWER) {
            assertThat(notes.findById(NOTE).orElseThrow().getCreatedBy()).isEqualTo(OWNER);
            assertThat(notes.findById(NOTE).orElseThrow().getUpdatedBy()).isEqualTo(member.getId());
        }
    }

    @ParameterizedTest
    @MethodSource("sharedRoles")
    void sharedWritesAreCappedByBothGrantAndMembership(ShareAccessLevel grant, TeamRole role) throws Exception {
        UserAccount member = user();
        teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), role));
        notebooks.share(BOOK, OWNER, bookTag(), new NotebookShareRequest(RECIPIENT_TEAM, grant));
        boolean editor = grant == ShareAccessLevel.EDITOR && role != TeamRole.VIEWER;
        var detail = mvc.perform(as(get(RESOURCE), member.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value(editor ? "EDITOR" : "VIEWER"))
                .andReturn().getResponse();
        mvc.perform(as(patch(RESOURCE), member.getId()).header("If-Match", detail.getHeader("ETag"))
                        .content(body(Map.of("content", "Shared revision"))))
                .andExpect(status().is(editor ? 200 : 403));
        var created = mvc.perform(as(post(COLLECTION), member.getId())
                        .content(body(Map.of("title", "Shared creation", "content", "Body"))))
                .andExpect(status().is(editor ? 201 : 403)).andReturn().getResponse();
        if (editor) {
            assertThat(json.readTree(created.getContentAsString()).path("teamId").asText())
                    .isEqualTo(OWNER_TEAM.toString());
            assertThat(json.readTree(created.getContentAsString()).path("createdBy").asText())
                    .isEqualTo(member.getId().toString());
        }
    }

    @Test
    void revocationAndNotebookArchiveRemoveEveryNotePath() throws Exception {
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isNotFound());
        mvc.perform(as(get(COLLECTION), RECIPIENT)).andExpect(status().isNotFound());
        notebooks.share(BOOK, OWNER, bookTag(), new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR));
        String recipientTag = noteTag(NOTE, RECIPIENT);
        notebooks.revoke(BOOK, OWNER, RECIPIENT_TEAM, bookTag());
        mvc.perform(as(get(RESOURCE), RECIPIENT)).andExpect(status().isNotFound());
        mvc.perform(as(patch(RESOURCE), RECIPIENT).header("If-Match", recipientTag)
                .content(body(Map.of("pinned", true)))).andExpect(status().isNotFound());
        notebooks.archive(BOOK, OWNER, bookTag());
        mvc.perform(as(get(RESOURCE), OWNER)).andExpect(status().isNotFound());
        mvc.perform(as(get(COLLECTION), OWNER)).andExpect(status().isNotFound());
        mvc.perform(as(post(COLLECTION), OWNER).content(body(Map.of("title", "Hidden", "content", ""))))
                .andExpect(status().isNotFound());
        assertThat(notes.existsById(NOTE)).isTrue();
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(1);
    }

    @Test
    void conditionsTrackNoteParentCallerAndRoleAndRejectUnsafeHeaderForms() throws Exception {
        String old = noteTag(NOTE, OWNER);
        mvc.perform(as(put(RESOURCE), OWNER).content(body(Map.of("title", "Title", "content", "", "pinned", false))))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch(RESOURCE), OWNER).content(body(Map.of("pinned", true))))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(as(delete(RESOURCE), OWNER)).andExpect(status().isPreconditionRequired());
        notebooks.update(BOOK, OWNER, bookTag(), new UpdateNotebookRequest("New notebook metadata", null));
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", old).content(body(Map.of("pinned", true))))
                .andExpect(status().isPreconditionFailed());
        String current = noteTag(NOTE, OWNER);
        for (String invalid : List.of("*", "W/" + current, current + "," + current, "invalid")) {
            mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", invalid)
                    .content(body(Map.of("pinned", true)))).andExpect(status().isBadRequest());
        }
        var note = notes.findById(NOTE).orElseThrow();
        String wrongActor = NoteEtags.value(NOTE, note.getVersion(), BOOK, 1, RECIPIENT, TeamRole.ADMIN);
        mvc.perform(as(delete(RESOURCE), OWNER).header("If-Match", wrongActor))
                .andExpect(status().isPreconditionFailed());
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(1);
    }

    @Test
    void explicitNullEmptyPatchUnknownFieldsAndIncompletePutAreRejected() throws Exception {
        String tag = noteTag(NOTE, OWNER);
        for (String field : List.of("title", "content", "pinned")) {
            mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", tag).content("{\"" + field + "\":null}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NULL_PATCH_FIELD"));
        }
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", tag).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EMPTY_PATCH"));
        mvc.perform(as(put(RESOURCE), OWNER).header("If-Match", tag)
                .content(body(Map.of("title", "Title", "content", "")))).andExpect(status().isBadRequest());
        mvc.perform(as(post(COLLECTION), OWNER).content(body(Map.of(
                "title", "Title", "content", "", "createdBy", RECIPIENT)))).andExpect(status().isBadRequest());
        mvc.perform(as(patch(RESOURCE), OWNER).header("If-Match", tag).content("{"))
                .andExpect(status().isBadRequest());
        for (String query : List.of("?size=0", "?size=101", "?page=-1", "?page=invalid")) {
            mvc.perform(as(get(COLLECTION + query), OWNER)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void enforcesContentByteBoundaryAndJsonBodyLimit() throws Exception {
        mvc.perform(as(post(COLLECTION), OWNER).content(body(Map.of(
                        "title", "Boundary", "content", "é".repeat(NoteLimits.CONTENT_BYTES / 2)))))
                .andExpect(status().isCreated());
        var contentOverflow = mvc.perform(as(post(COLLECTION), OWNER).content(body(Map.of(
                "title", "Oversized", "content", "é".repeat(NoteLimits.CONTENT_BYTES / 2 + 1))))).andReturn();
        Throwable contentError = contentOverflow.getResolvedException();
        var codes = contentError instanceof org.springframework.web.bind.MethodArgumentNotValidException invalid
                ? invalid.getBindingResult().getFieldErrors().stream().map(error -> error.getCode()).toList()
                : List.of(contentError == null ? "none" : contentError.getClass().getSimpleName());
        assertThat(contentOverflow.getResponse().getStatus()).withFailMessage("Content status %s; error codes %s",
                contentOverflow.getResponse().getStatus(), codes).isEqualTo(413);
        assertThat(json.readTree(contentOverflow.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("CONTENT_TOO_LARGE");
        String oversizedBody = " ".repeat(NoteLimits.BODY_BYTES + 1) + "{\"title\":\"Title\",\"content\":\"\"}";
        var oversized = mvc.perform(as(post(COLLECTION), OWNER).content(oversizedBody)).andReturn();
        var causes = new java.util.ArrayList<String>();
        Throwable cause = oversized.getResolvedException();
        while (cause != null) {
            causes.add(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        assertThat(oversized.getResponse().getStatus()).withFailMessage("Body status %s; exception types %s",
                oversized.getResponse().getStatus(), causes).isEqualTo(413);
        assertThat(json.readTree(oversized.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("BODY_TOO_LARGE");
    }

    @Test
    void updatesPreserveExistingTagsInResponsesAndSnapshots() throws Exception {
        Tag tag = tags.saveAndFlush(new Tag(OWNER_TEAM, "design"));
        noteTags.saveAndFlush(new NoteTag(NOTE, tag.getId(), OWNER_TEAM));
        mvc.perform(as(put(RESOURCE), OWNER).header("If-Match", noteTag(NOTE, OWNER))
                        .content(body(Map.of("title", "Tagged revision", "content", "Body", "pinned", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0]").value("design"));
        var revisions = history.findByNoteId(NOTE, PageRequest.of(0, 20)).getContent();
        var latest = revisions.stream().filter(row -> row.getVersionNumber() == 2).findFirst().orElseThrow();
        assertThat(json.readTree(latest.getTagsSnapshot()).get(0).asText()).isEqualTo("design");
        assertThat(latest.isPinned()).isTrue();
    }

    @Test
    void allSixRoutesRequireBearerAuthentication() throws Exception {
        mvc.perform(post(COLLECTION).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(COLLECTION)).andExpect(status().isUnauthorized());
        mvc.perform(get(RESOURCE)).andExpect(status().isUnauthorized());
        mvc.perform(put(RESOURCE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(RESOURCE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(RESOURCE)).andExpect(status().isUnauthorized());
    }

    private UserAccount user() {
        return users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(OWNER).orElseThrow().getPasswordHash(), "Note contributor"));
    }

    private String noteTag(UUID noteId, UUID actor) throws Exception {
        return mvc.perform(as(get("/api/v1/notes/" + noteId), actor)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    private String bookTag() {
        var book = notebooks.detail(BOOK, OWNER);
        return NotebookEtags.value(BOOK, book.version(), OWNER, TeamRole.ADMIN);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.issue(actor).accessToken());
    }

    private String body(Object value) {
        return json.writeValueAsString(value);
    }

    private long activityCount(UUID noteId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE entity_type = 'note' "
                + "AND entity_id = UUID_TO_BIN(?)", Long.class, noteId.toString());
    }

    private static Stream<Arguments> sharedRoles() {
        return Arrays.stream(ShareAccessLevel.values()).flatMap(grant ->
                Arrays.stream(TeamRole.values()).map(role -> Arguments.of(grant, role)));
    }

    private static UUID id(int type, int index) {
        return UUID.fromString(type + "0000000-0000-4000-8000-00000000000" + index);
    }
}
