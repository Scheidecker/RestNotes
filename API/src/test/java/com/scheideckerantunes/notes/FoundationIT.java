package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the embedded HTTP server, not just the mock servlet environment.
 *
 * @author Carlos Scheidecker
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FoundationIT extends MySqlIntegrationSupport {

    @LocalServerPort
    private int port;

    @Test
    void packagedServerSurfaceIsAvailableWithoutCreatingASession() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> health = get(client, "/actuator/health");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components");
            assertThat(health.headers().firstValue("set-cookie")).isEmpty();

            assertThat(get(client, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            assertThat(get(client, "/actuator/info").body()).contains("Carlos Scheidecker");
            HttpResponse<String> openApi = get(client, "/v3/api-docs");
            assertThat(openApi.statusCode()).isEqualTo(200);
            assertThat(openApi.body()).contains("\"title\":\"RestNotes\"");
            assertThat(get(client, "/swagger-ui/index.html").statusCode()).isEqualTo(200);
            assertThat(get(client, "/api/v1/notes").statusCode()).isEqualTo(401);
            assertThat(get(client, "/actuator/env").statusCode()).isEqualTo(401);
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
