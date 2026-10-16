package com.scheideckerantunes.notes;

import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared disposable MySQL, never the developer's Compose volume; Docker is required.
 *
 * @author Carlos Scheidecker
 */
abstract class MySqlIntegrationSupport {

    static final DockerImageName MYSQL_IMAGE = DockerImageName.parse(
            "mysql:8.4.11@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70")
            .asCompatibleSubstituteFor("mysql");

    private static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE)
            .withDatabaseName("RestNotes")
            .withUsername("APIUser")
            .withPassword(UUID.randomUUID().toString());

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("restnotes.security.jwt.private-key-location", JwtTestKeys::privateLocation);
        registry.add("restnotes.security.jwt.public-key-location", JwtTestKeys::publicLocation);
    }
}
