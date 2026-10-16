package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.scheideckerantunes.notes.team.TeamMemberId;
import com.scheideckerantunes.notes.team.TeamMemberRepository;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end auth tests never print request bodies, credentials or issued access tokens.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = "restnotes.demo.enabled=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Transactional
class AuthenticationIT extends MySqlIntegrationSupport {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private TeamMemberRepository members;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JwtDecoder decoder;

    @Test
    void registersWithoutMembershipsThenAuthenticatesAndReadsProfile() throws Exception {
        String email = UUID.randomUUID() + "@scheideckerantunes.com";
        String password = UUID.randomUUID().toString();
        var registration = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", " " + email.toUpperCase() + " ",
                                "password", password, "fullName", " New User "))))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/users/me"))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.fullName").value("New User"))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn().getResponse();
        UUID id = UUID.fromString(json.readTree(registration.getContentAsString()).path("id").asText());
        assertThat(passwords.matches(password, users.findById(id).orElseThrow().getPasswordHash())).isTrue();
        String token = login(email, password);
        assertThat(decoder.decode(token).getSubject()).isEqualTo(id.toString());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/v1/users/me/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void seededAdminsSeeOnlyTheirOwnCurrentTeam() throws Exception {
        for (String team : List.of("engineering", "marketing")) {
            String token = login(team + ".admin@scheideckerantunes.com", "RestNotesDemo#8");
            mvc.perform(get("/api/v1/users/me/teams").param("page", "0").param("size", "1")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].role").value("ADMIN"))
                    .andExpect(jsonPath("$.items[0].name").value(team.equals("engineering")
                            ? "Engineering" : "Marketing"));
        }
    }

    @Test
    void membershipRemovalTakesEffectWithoutReissuingTheToken() throws Exception {
        String token = login("engineering.admin@scheideckerantunes.com", "RestNotesDemo#8");
        members.deleteById(new TeamMemberId(
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000001")));
        members.flush();
        mvc.perform(get("/api/v1/users/me/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void duplicateNormalizedEmailReturnsConflictWithoutLeakingCredentials() throws Exception {
        String request = body(Map.of("email", " ENGINEERING.ADMIN@scheideckerantunes.com ",
                "password", UUID.randomUUID().toString(), "fullName", "Duplicate"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRegistrationCreatesOnlyOneAccount() throws Exception {
        String email = UUID.randomUUID() + "@scheideckerantunes.com";
        String payload = body(Map.of("email", email, "password", UUID.randomUUID().toString(), "fullName", "Race"));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls = List.of(pool.submit(() -> registerAfter(start, payload)),
                    pool.submit(() -> registerAfter(start, payload)));
            start.countDown();
            assertThat(List.of(calls.getFirst().get(), calls.getLast().get())).containsExactlyInAnyOrder(201, 409);
            assertThat(users.findByEmail(email)).isPresent();
        } finally {
            users.findByEmail(email).ifPresent(users::delete);
        }
    }

    @Test
    void unknownAndIncorrectCredentialsHaveTheSameGenericFailure() throws Exception {
        String wrong = UUID.randomUUID().toString();
        for (String email : List.of("missing@scheideckerantunes.com", "engineering.admin@scheideckerantunes.com")) {
            var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of("email", email, "password", wrong))))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid credentials"))
                    .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse();
            assertThat(response.getContentAsString().contains(wrong)).isFalse();
        }
    }

    @Test
    void rejectsInvalidBodiesUnknownFieldsAndOversizedUnicodePasswords() throws Exception {
        String validPassword = UUID.randomUUID().toString();
        for (Map<String, String> payload : List.of(
                Map.of("email", "bad-email", "password", validPassword, "fullName", "Test"),
                Map.of("email", "valid@scheideckerantunes.com", "password", "short", "fullName", "Test"),
                Map.of("email", "valid@scheideckerantunes.com", "password", "é".repeat(37), "fullName", "Test"),
                Map.of("email", "valid@scheideckerantunes.com", "password", validPassword,
                        "fullName", "Test", "role", "ADMIN"))) {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatesOnlyBearerHeadersAndDoesNotUnlockOtherRoutes() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
        String token = login("engineering.admin@scheideckerantunes.com", "RestNotesDemo#8");
        mvc.perform(get("/api/v1/users/me").param("access_token", token)).andExpect(status().isUnauthorized());
        for (String route : List.of("/api/v1/notes", "/actuator/env", "/actuator/configprops")) {
            mvc.perform(get(route).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void validatesPaginationAndPreservesSafeCorrelationIds() throws Exception {
        String token = login("engineering.admin@scheideckerantunes.com", "RestNotesDemo#8");
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=invalid")) {
            mvc.perform(get("/api/v1/users/me/teams" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/users/me").header("X-Correlation-Id", "auth-test"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-Id", "auth-test"))
                .andExpect(jsonPath("$.correlationId").value("auth-test"));
    }

    private String login(String email, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("accessToken").asText();
    }

    private String body(Map<String, String> values) {
        return json.writeValueAsString(values);
    }

    private int registerAfter(CountDownLatch start, String payload) throws Exception {
        start.await();
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse().getStatus();
    }
}
