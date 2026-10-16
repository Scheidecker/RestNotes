package com.scheideckerantunes.notes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the development operational surface and fail-closed business boundary.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FoundationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsPublicWithoutDetailsOrSession() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void healthProbesArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void infoContainsOnlyApprovedApplicationMetadata() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("RestNotes"))
                .andExpect(jsonPath("$.app.author").value("Carlos Scheidecker"))
                .andExpect(jsonPath("$.java").doesNotExist())
                .andExpect(jsonPath("$.os").doesNotExist())
                .andExpect(jsonPath("$.process").doesNotExist());
    }

    @Test
    void openApiDescribesOnlyTheFoundation() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("RestNotes"))
                .andExpect(jsonPath("$.info.contact.name").value("Carlos Scheidecker"))
                .andExpect(jsonPath("$.paths['/api/v1/notes']").doesNotExist());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
    }

    @Test
    void swaggerRedirectAndAssetsArePublicInDevelopment() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void businessAndSensitiveOperationalRoutesAreDenied() throws Exception {
        for (String path : new String[] {"/api/v1/notes", "/actuator/env", "/actuator/configprops", "/actuator"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/register")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login")).andExpect(status().isUnauthorized());
    }
}
