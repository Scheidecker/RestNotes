package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import liquibase.UpdateSummaryEnum;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Tests clean schema, explicit-context demo safety, repeat migration and DB grants.
 *
 * @author Carlos Scheidecker
 */
class MigrationLifecycleIT {

    @Test
    void schemaOnlyThenExplicitDemoIsRepeatableAndRestrictedToTheApplicationDatabase() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MySqlIntegrationSupport.MYSQL_IMAGE)
                .withDatabaseName("RestNotes").withUsername("APIUser").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            DriverManagerDataSource source = new DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(source);
            migrate(source, "schema.yml", null);
            assertThat(count(jdbc, "users")).isZero();
            assertThat(count(jdbc, "DATABASECHANGELOG")).isEqualTo(2);
            migrate(source, "demo.yml", null);
            assertThat(count(jdbc, "users")).isZero();
            migrate(source, "demo.yml", "schema");
            assertThat(count(jdbc, "users")).isZero();
            migrate(source, "demo.yml", "demo");
            for (String table : new String[] {"users", "teams", "team_members", "notebooks",
                    "notes", "note_history", "activity_logs"}) {
                assertThat(count(jdbc, table)).as(table).isEqualTo(2);
            }
            jdbc.update("UPDATE notes SET title = 'Preserved edit' WHERE id = UUID_TO_BIN(?)",
                    "40000000-0000-4000-8000-000000000001");
            migrate(source, "demo.yml", "demo");
            migrate(source, "schema.yml", null);
            assertThat(count(jdbc, "users")).isEqualTo(2);
            assertThat(count(jdbc, "DATABASECHANGELOG")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT title FROM notes WHERE id = UUID_TO_BIN(?)",
                    String.class, "40000000-0000-4000-8000-000000000001")).isEqualTo("Preserved edit");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notes WHERE MATCH(title, content) AGAINST('architecture')",
                    Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT COUNT(*) FROM mysql.user", Integer.class))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.execute("CREATE DATABASE MustNotBeAllowed"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)).startsWith("APIUser@");
        }
    }

    private void migrate(DriverManagerDataSource source, String entryPoint, String contexts) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setChangeLog("classpath:db/changelog/" + entryPoint);
        liquibase.setContexts(contexts);
        liquibase.setAnalyticsEnabled(false);
        liquibase.setShowSummary(UpdateSummaryEnum.OFF);
        liquibase.afterPropertiesSet();
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
