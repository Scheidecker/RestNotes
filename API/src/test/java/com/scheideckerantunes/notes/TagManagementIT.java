package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scheideckerantunes.notes.auth.TokenService;
import com.scheideckerantunes.notes.history.NoteHistoryRepository;
import com.scheideckerantunes.notes.note.AddTagRequest;
import com.scheideckerantunes.notes.note.CreateNoteRequest;
import com.scheideckerantunes.notes.note.NoteEtags;
import com.scheideckerantunes.notes.note.NoteRepository;
import com.scheideckerantunes.notes.note.NoteService;
import com.scheideckerantunes.notes.note.TagRepository;
import com.scheideckerantunes.notes.notebook.NotebookEtags;
import com.scheideckerantunes.notes.notebook.NotebookService;
import com.scheideckerantunes.notes.notebook.NotebookShareRequest;
import com.scheideckerantunes.notes.notebook.ShareAccessLevel;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.net.URI;
import jakarta.persistence.EntityManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * Tag mutations use real identity, tenant constraints and note snapshot transactions.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional(isolation = Isolation.READ_COMMITTED)
class TagManagementIT extends MySqlIntegrationSupport {
    private static final UUID OWNER = id(1, 1);
    private static final UUID RECIPIENT = id(1, 2);
    private static final UUID OWNER_TEAM = id(2, 1);
    private static final UUID RECIPIENT_TEAM = id(2, 2);
    private static final UUID BOOK = id(3, 1);
    private static final UUID NOTE = id(4, 1);
    private static final String RESOURCE = "/api/v1/notes/" + NOTE;
    private static final String TAGS = RESOURCE + "/tags";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
    @Autowired
    private NoteService notes;
    @Autowired
    private NoteRepository noteRepository;
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
    private EntityManager entityManager;

    @Test
    void normalizedAddsAndRemovalsUpdateVersionSnapshotsAndActivityOnlyOnRealChange() throws Exception {
        String original = noteTag(NOTE, OWNER);
        String markdown = noteRepository.findById(NOTE).orElseThrow().getContent();
        var added = mvc.perform(as(post(TAGS), OWNER).header("If-Match", original).content(body(" Design Review ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0]").value("design review"))
                .andExpect(jsonPath("$.version").value(1)).andReturn().getResponse();
        String current = added.getHeader("ETag");
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(2);
        assertThat(actionCount(NOTE, "NOTE_TAGGED")).isEqualTo(1);
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", current).content(body("DESIGN REVIEW")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", current));
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(2);
        var removed = mvc.perform(as(delete(tagUri(NOTE, "DESIGN REVIEW")), OWNER).header("If-Match", current))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        mvc.perform(as(delete(tagUri(NOTE, "design review")), OWNER).header("If-Match", removed.getHeader("ETag")))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", removed.getHeader("ETag")));
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(3);
        assertThat(actionCount(NOTE, "NOTE_UNTAGGED")).isEqualTo(1);
        assertThat(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, "design review")).isPresent();
        var latest = history.findByNoteId(NOTE, PageRequest.of(0, 20)).getContent().stream()
                .filter(row -> row.getVersionNumber() == 3).findFirst().orElseThrow();
        assertThat(json.readTree(latest.getTagsSnapshot()).isEmpty()).isTrue();
        assertThat(noteRepository.findById(NOTE).orElseThrow().getContent().equals(markdown)).isTrue();
    }

    @Test
    void databaseCollationAliasesReuseTheStoredCanonicalName() throws Exception {
        String current = add("café", noteTag(NOTE, OWNER));
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", current).content(body("CAFE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags[0]").value("café"))
                .andExpect(header().string("ETag", current));
        assertThat(catalog.count()).isEqualTo(1);
        mvc.perform(as(delete(tagUri(NOTE, "CAFE")), OWNER).header("If-Match", current))
                .andExpect(status().isNoContent());
    }

    @Test
    void encodedUnicodeSpacePlusAndHashNamesRoundTrip() throws Exception {
        for (String name : List.of("design review", "c++", "c#", "café")) {
            String current = add(name, noteTag(NOTE, OWNER));
            mvc.perform(as(delete(tagUri(NOTE, name)), OWNER).header("If-Match", current))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(as(get(RESOURCE), OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.tags").isEmpty());
    }

    @Test
    void capacityIsTwentyAndNoOpsRemainValidAtCapacity() throws Exception {
        String current = noteTag(NOTE, OWNER);
        for (int index = 0; index < 20; index++) {
            current = add("tag-" + index, current);
        }
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", current).content(body("TAG-0")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", current));
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", current).content(body("overflow")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAG_LIMIT"));
        mvc.perform(as(delete(tagUri(NOTE, "absent")), OWNER).header("If-Match", current))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", current));
        assertThat(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, "overflow")).isEmpty();
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(21);
        assertThat(noteRepository.findById(NOTE).orElseThrow().getVersion()).isEqualTo(20);
    }

    @Test
    void catalogIsReusedAcrossNotesButNeverAcrossOwningTeams() throws Exception {
        var other = notes.create(BOOK, OWNER, new CreateNoteRequest("Other note", "", false));
        add("design", noteTag(NOTE, OWNER));
        notes.addTag(other.id(), OWNER, NoteEtags.from(other, OWNER), new AddTagRequest("DESIGN"));
        var ownerTag = catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, "design").orElseThrow();
        assertThat(catalog.count()).isEqualTo(1);
        UUID otherTeamNote = id(4, 2);
        notes.addTag(otherTeamNote, RECIPIENT, noteTag(otherTeamNote, RECIPIENT), new AddTagRequest("design"));
        assertThat(catalog.findByTeamIdAndNormalizedName(RECIPIENT_TEAM, "design").orElseThrow().getId())
                .isNotEqualTo(ownerTag.getId());
        mvc.perform(as(delete(tagUri(NOTE, "design")), OWNER).header("If-Match", noteTag(NOTE, OWNER)))
                .andExpect(status().isNoContent());
        assertThat(notes.detail(other.id(), OWNER).tags()).containsExactly("design");
    }

    @ParameterizedTest
    @MethodSource("sharedRoles")
    void sharedMutationsUseCappedAccessAndTheNotesOwningTeam(ShareAccessLevel grant, TeamRole role) throws Exception {
        var member = users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(OWNER).orElseThrow().getPasswordHash(), "Tag collaborator"));
        teams.addMember(RECIPIENT_TEAM, RECIPIENT, new AddTeamMemberRequest(member.getEmail(), role));
        notebooks.share(BOOK, OWNER, bookTag(), new NotebookShareRequest(RECIPIENT_TEAM, grant));
        boolean editor = grant == ShareAccessLevel.EDITOR && role != TeamRole.VIEWER;
        var result = mvc.perform(as(post(TAGS), member.getId()).header("If-Match", noteTag(NOTE, member.getId()))
                        .content(body("scoped")))
                .andExpect(status().is(editor ? 200 : 403)).andReturn().getResponse();
        if (editor) {
            assertThat(catalog.findByTeamIdAndNormalizedName(OWNER_TEAM, "scoped")).isPresent();
            assertThat(catalog.findByTeamIdAndNormalizedName(RECIPIENT_TEAM, "scoped")).isEmpty();
            assertThat(noteRepository.findById(NOTE).orElseThrow().getUpdatedBy()).isEqualTo(member.getId());
            mvc.perform(as(delete(tagUri(NOTE, "scoped")), member.getId())
                            .header("If-Match", result.getHeader("ETag"))).andExpect(status().isNoContent());
        } else {
            mvc.perform(as(delete(tagUri(NOTE, "absent")), member.getId())
                            .header("If-Match", noteTag(NOTE, member.getId()))).andExpect(status().isForbidden());
        }
    }

    @Test
    void conditionsAndValidationNeverCreatePartialLabels() throws Exception {
        String original = noteTag(NOTE, OWNER);
        mvc.perform(as(post(TAGS), OWNER).content(body("valid"))).andExpect(status().isPreconditionRequired());
        mvc.perform(as(delete(tagUri(NOTE, "valid")), OWNER)).andExpect(status().isPreconditionRequired());
        String current = add("valid", original);
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", original).content(body("stale")))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete(tagUri(NOTE, "valid")), OWNER).header("If-Match", original))
                .andExpect(status().isPreconditionFailed());
        for (String invalid : List.of("*", "W/" + current, current + "," + current)) {
            mvc.perform(as(post(TAGS), OWNER).header("If-Match", invalid).content(body("invalid")))
                    .andExpect(status().isBadRequest());
        }
        for (String invalid : List.of("", " ", ".", "..", "a/b", "a\\b", "a;b", "a%b",
                "line\nbreak", "n".repeat(51), "İ".repeat(26))) {
            mvc.perform(as(post(TAGS), OWNER).header("If-Match", current).content(body(invalid)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", current)
                        .content(json.writeValueAsString(Map.of("name", "injected", "teamId", RECIPIENT_TEAM))))
                .andExpect(status().isBadRequest());
        assertThat(catalog.count()).isEqualTo(1);
        assertThat(history.findLatestVersion(NOTE)).isEqualTo(2);
    }

    @Test
    void revokedAndArchivedResourcesCannotBeTagged() throws Exception {
        notebooks.share(BOOK, OWNER, bookTag(), new NotebookShareRequest(RECIPIENT_TEAM, ShareAccessLevel.EDITOR));
        var tagged = notes.addTag(NOTE, RECIPIENT, noteTag(NOTE, RECIPIENT), new AddTagRequest("retained"));
        String recipientTag = NoteEtags.from(tagged, RECIPIENT);
        notebooks.revoke(BOOK, OWNER, RECIPIENT_TEAM, bookTag());
        mvc.perform(as(post(TAGS), RECIPIENT).header("If-Match", recipientTag).content(body("denied")))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(tagUri(NOTE, "retained")), RECIPIENT).header("If-Match", recipientTag))
                .andExpect(status().isNotFound());
        String archiveTag = notes.archive(NOTE, OWNER, noteTag(NOTE, OWNER));
        mvc.perform(as(post(TAGS), OWNER).header("If-Match", archiveTag).content(body("denied")))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(tagUri(NOTE, "retained")), OWNER).header("If-Match", archiveTag))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM note_tags WHERE note_id = UUID_TO_BIN(?)",
                Integer.class, NOTE.toString())).isEqualTo(1);
    }

    @Test
    void bothRoutesRequireAuthenticationAndNoGlobalDirectoryIsExposed() throws Exception {
        mvc.perform(post(TAGS).contentType(MediaType.APPLICATION_JSON).content(body("test")))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(tagUri(NOTE, "test"))).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/tags"), OWNER)).andExpect(status().isForbidden());
    }

    private String add(String name, String etag) throws Exception {
        return mvc.perform(as(post(TAGS), OWNER).header("If-Match", etag).content(body(name)))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
    }

    private URI tagUri(UUID noteId, String name) {
        return URI.create("/api/v1/notes/" + noteId + "/tags/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private String noteTag(UUID noteId, UUID actor) {
        return NoteEtags.from(notes.detail(noteId, actor), actor);
    }

    private String bookTag() {
        return NotebookEtags.value(BOOK, notebooks.detail(BOOK, OWNER).version(), OWNER, TeamRole.ADMIN);
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actor) {
        return request.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokens.issue(actor).accessToken());
    }

    private String body(String name) {
        return json.writeValueAsString(Map.of("name", name));
    }

    private int actionCount(UUID noteId, String action) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM activity_logs WHERE entity_id = UUID_TO_BIN(?) "
                + "AND action = ?", Integer.class, noteId.toString(), action);
    }

    private static Stream<Arguments> sharedRoles() {
        return Arrays.stream(ShareAccessLevel.values()).flatMap(grant ->
                Arrays.stream(TeamRole.values()).map(role -> Arguments.of(grant, role)));
    }

    private static UUID id(int type, int index) {
        return UUID.fromString(type + "0000000-0000-4000-8000-00000000000" + index);
    }
}
