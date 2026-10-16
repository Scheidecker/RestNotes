package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Ensures the production skeleton does not publish documentation or business routes.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(properties = {"spring.liquibase.enabled=true", "restnotes.demo.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "prod"})
class ProductionSurfaceIT extends MySqlIntegrationSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void productionDisablesOpenApiAndSwagger() throws Exception {
        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
        for (String path : new String[] {"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html",
                "/swagger-ui/index.html", "/api/v1/notes"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void approvedHealthAndInfoRemainAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void registrationIsDisabledByDefaultInProduction() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"disabled@scheideckerantunes.com",
                                 "password":"public-test-value","fullName":"Disabled"}
                                """))
                .andExpect(status().isForbidden());
    }
}
