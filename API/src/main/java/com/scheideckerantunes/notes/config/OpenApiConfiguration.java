package com.scheideckerantunes.notes.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API without advertising business routes that do not yet exist.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI restNotesOpenApi() {
        return new OpenAPI().components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .info(new Info()
                .title("RestNotes")
                .version("v1")
                .description("Identity, team administration, notebook management and cross-team sharing APIs.")
                .contact(new Contact().name("Carlos Scheidecker")));
    }
}
