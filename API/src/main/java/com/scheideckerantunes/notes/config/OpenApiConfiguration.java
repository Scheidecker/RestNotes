package com.scheideckerantunes.notes.config;

import io.swagger.v3.oas.models.OpenAPI;
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
        return new OpenAPI().info(new Info()
                .title("RestNotes")
                .version("v1")
                .description("Small-team notes API. Phase 1 foundation; business endpoints are not implemented.")
                .contact(new Contact().name("Carlos Scheidecker")));
    }
}
