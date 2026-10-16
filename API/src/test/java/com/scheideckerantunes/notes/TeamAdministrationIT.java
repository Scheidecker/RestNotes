package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scheideckerantunes.notes.activity.ActivityLogRepository;
import com.scheideckerantunes.notes.auth.TokenService;
import com.scheideckerantunes.notes.team.AddTeamMemberRequest;
import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.team.TeamRepository;
import com.scheideckerantunes.notes.team.TeamRole;
import com.scheideckerantunes.notes.team.TeamService;
import com.scheideckerantunes.notes.user.UserAccount;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Real JWT/MySQL endpoint tests roll back fixture edits and never print bearer tokens.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class TeamAdministrationIT extends MySqlIntegrationSupport {
    private static final UUID ENGINEERING = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MARKETING = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ADMIN = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final String BASE = "/api/v1/teams";
    private static final String MEMBERS = BASE + "/" + ENGINEERING + "/members";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
    @Autowired
    private TeamService service;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private ActivityLogRepository activity;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void creatorBecomesAdminAndTeamDiscoveryIsPaginatedAndScoped() throws Exception {
        var result = mvc.perform(as(post(BASE), ADMIN).content(body(Map.of(
                        "name", " A collaboration ", "description", " New project "))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.name").value("A collaboration")).andReturn().getResponse();
        UUID teamId = UUID.fromString(json.readTree(result.getContentAsString()).path("id").asText());
        assertThat(teams.existsById(teamId)).isTrue();
        assertThat(result.getHeader("Location")).isEqualTo(BASE + "/" + teamId);
        assertThat(members.findById(new TeamMemberId(teamId, ADMIN)).orElseThrow().getRole())
                .isEqualTo(TeamRole.ADMIN);
        assertThat(activity.findByTeamId(teamId, PageRequest.of(0, 20)).getContent().getFirst().getAction())
                .isEqualTo("TEAM_CREATED");
        mvc.perform(as(get(BASE + "/" + teamId), ADMIN)).andExpect(status().isOk());
        mvc.perform(as(get(BASE).param("size", "1"), ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("A collaboration"));
        mvc.perform(as(get(BASE).param("page", "1").param("size", "1"), ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("Engineering"));
        mvc.perform(as(get(BASE), OTHER_ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Marketing"));
    }

    @Test
    void outsidersCannotDiscoverOrMutateAnotherTeamsResources() throws Exception {
        String other = BASE + "/" + MARKETING;
        mvc.perform(as(get(other), ADMIN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));
        mvc.perform(as(get(other + "/members"), ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(post(other + "/members"), ADMIN).content(body(Map.of(
                "email", users.findById(ADMIN).orElseThrow().getEmail(), "role", "VIEWER"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(patch(other + "/members/" + OTHER_ADMIN), ADMIN).content(body(Map.of("role", "EDITOR"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(delete(other + "/members/" + OTHER_ADMIN), ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(get(BASE + "/" + UUID.randomUUID()), ADMIN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Resource not found"));
    }

    @Test
    void adminAddsUpdatesAndRemovesAnExistingMemberWithoutExposingCredentials() throws Exception {
        UserAccount invited = user();
        mvc.perform(as(post(MEMBERS), ADMIN).content(body(Map.of(
                        "email", " " + invited.getEmail().toUpperCase() + " ", "role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", MEMBERS + "/" + invited.getId()))
                .andExpect(jsonPath("$.userId").value(invited.getId().toString()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(as(get(MEMBERS).param("size", "1"), ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
        mvc.perform(as(post(MEMBERS), ADMIN).content(body(Map.of("email", invited.getEmail(), "role", "VIEWER"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEMBER_ALREADY_EXISTS"));
        mvc.perform(as(patch(MEMBERS + "/" + invited.getId()), ADMIN).content(body(Map.of("role", "EDITOR"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("EDITOR"));
        mvc.perform(as(get("/api/v1/users/me/teams"), invited.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("EDITOR"));
        long before = activityCount(ENGINEERING);
        mvc.perform(as(patch(MEMBERS + "/" + invited.getId()), ADMIN).content(body(Map.of("role", "EDITOR"))))
                .andExpect(status().isOk());
        assertThat(activityCount(ENGINEERING)).isEqualTo(before);
        mvc.perform(as(delete(MEMBERS + "/" + invited.getId()), ADMIN)).andExpect(status().isNoContent());
        mvc.perform(as(delete(MEMBERS + "/" + invited.getId()), ADMIN)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/users/me/teams"), invited.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = TeamRole.class, names = {"EDITOR", "VIEWER"})
    void nonAdminsCanReadButCannotChangeMemberships(TeamRole role) throws Exception {
        UserAccount member = user();
        service.addMember(ENGINEERING, ADMIN, new AddTeamMemberRequest(member.getEmail(), role));
        mvc.perform(as(get(BASE + "/" + ENGINEERING), member.getId())).andExpect(status().isOk());
        mvc.perform(as(get(MEMBERS), member.getId())).andExpect(status().isOk());
        mvc.perform(as(post(MEMBERS), member.getId()).content(body(Map.of(
                "email", users.findById(OTHER_ADMIN).orElseThrow().getEmail(), "role", "ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(as(patch(MEMBERS + "/" + member.getId()), member.getId())
                        .content(body(Map.of("role", "ADMIN")))).andExpect(status().isForbidden());
        mvc.perform(as(delete(MEMBERS + "/" + member.getId()), member.getId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void lastAdminCannotDemoteOrRemoveSelf() throws Exception {
        long before = activityCount(ENGINEERING);
        mvc.perform(as(patch(MEMBERS + "/" + ADMIN), ADMIN).content(body(Map.of("role", "VIEWER"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_TEAM_ADMIN"));
        mvc.perform(as(delete(MEMBERS + "/" + ADMIN), ADMIN)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_TEAM_ADMIN"));
        assertThat(members.findById(new TeamMemberId(ENGINEERING, ADMIN)).orElseThrow().getRole())
                .isEqualTo(TeamRole.ADMIN);
        assertThat(activityCount(ENGINEERING)).isEqualTo(before);
    }

    @Test
    void adminHandoffAndRemovalImmediatelyChangeAccessForExistingTokens() throws Exception {
        UserAccount successor = user();
        String oldToken = tokens.issue(ADMIN).accessToken();
        service.addMember(ENGINEERING, ADMIN, new AddTeamMemberRequest(successor.getEmail(), TeamRole.ADMIN));
        mvc.perform(as(patch(MEMBERS + "/" + ADMIN), ADMIN).content(body(Map.of("role", "VIEWER"))))
                .andExpect(status().isOk());
        mvc.perform(post(MEMBERS).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + oldToken)
                        .content(body(Map.of("email", successor.getEmail(), "role", "ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete(MEMBERS + "/" + ADMIN), successor.getId())).andExpect(status().isNoContent());
        mvc.perform(get(BASE + "/" + ENGINEERING).header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void staleManagedActorRoleCannotBypassAuthorization() throws Exception {
        members.findById(new TeamMemberId(ENGINEERING, ADMIN)).orElseThrow();
        jdbc.update("UPDATE team_members SET role = 'EDITOR' WHERE team_id = UUID_TO_BIN(?) "
                + "AND user_id = UUID_TO_BIN(?)", ENGINEERING.toString(), ADMIN.toString());
        mvc.perform(as(post(MEMBERS), ADMIN).content(body(Map.of(
                "email", users.findById(OTHER_ADMIN).orElseThrow().getEmail(), "role", "VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingUsersAndMismatchedNestedMembershipIdsReturnNotFound() throws Exception {
        mvc.perform(as(post(MEMBERS), ADMIN).content(body(Map.of(
                "email", UUID.randomUUID() + "@scheideckerantunes.com", "role", "VIEWER"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(patch(MEMBERS + "/" + OTHER_ADMIN), ADMIN).content(body(Map.of("role", "EDITOR"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(patch(MEMBERS + "/" + UUID.randomUUID()), ADMIN).content(body(Map.of("role", "EDITOR"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void validatesBodiesRoleNamesIdsAndPagination() throws Exception {
        for (Map<String, String> payload : java.util.List.of(
                Map.of("name", " "), Map.of("name", "n".repeat(101)),
                Map.of("name", "Valid", "description", "d".repeat(2001)),
                Map.of("name", "Valid", "createdBy", OTHER_ADMIN.toString()))) {
            mvc.perform(as(post(BASE), ADMIN).content(body(payload))).andExpect(status().isBadRequest());
        }
        for (Map<String, String> payload : java.util.List.of(
                Map.of("email", "not-an-email", "role", "VIEWER"),
                Map.of("email", "user@scheideckerantunes.com"),
                Map.of("email", "user@scheideckerantunes.com", "role", "admin"))) {
            mvc.perform(as(post(MEMBERS), ADMIN).content(body(payload))).andExpect(status().isBadRequest());
        }
        mvc.perform(as(patch(MEMBERS + "/" + ADMIN), ADMIN).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(as(get(BASE + "/invalid"), ADMIN)).andExpect(status().isBadRequest());
        for (String path : new String[] {BASE, MEMBERS}) {
            for (String query : new String[] {"?page=-1", "?size=0", "?size=101", "?page=invalid"}) {
                mvc.perform(as(get(path + query), ADMIN)).andExpect(status().isBadRequest());
            }
        }
    }

    @Test
    void allTeamOperationsRequireBearerAuthentication() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Team\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + ENGINEERING)).andExpect(status().isUnauthorized());
        mvc.perform(get(MEMBERS)).andExpect(status().isUnauthorized());
        mvc.perform(post(MEMBERS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(MEMBERS + "/" + ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(MEMBERS + "/" + ADMIN)).andExpect(status().isUnauthorized());
    }

    private UserAccount user() {
        return users.saveAndFlush(new UserAccount(UUID.randomUUID() + "@scheideckerantunes.com",
                users.findById(ADMIN).orElseThrow().getPasswordHash(), "Invited member"));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID actorId) {
        return request.header("Authorization", "Bearer " + tokens.issue(actorId).accessToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String body(Map<String, String> value) {
        return json.writeValueAsString(value);
    }

    private long activityCount(UUID teamId) {
        return activity.findByTeamId(teamId, PageRequest.of(0, 100)).getTotalElements();
    }
}
