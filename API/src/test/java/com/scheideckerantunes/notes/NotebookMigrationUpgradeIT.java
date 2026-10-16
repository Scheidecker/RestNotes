package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import liquibase.UpdateSummaryEnum;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * An already seeded pre-sharing database upgrades without resets or checksum edits.
 *
 * @author Carlos Scheidecker
 */
class NotebookMigrationUpgradeIT {
    @Test
    void upgradesAppliedSchemaAndPreservesExistingEdits() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer(MySqlIntegrationSupport.MYSQL_IMAGE)
                .withDatabaseName("RestNotes").withUsername("APIUser").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            DriverManagerDataSource source = new DriverManagerDataSource(
                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(source);
            migrate(source, "legacy-notebooks.yml", "demo");
            jdbc.update("UPDATE notebooks SET title = 'Preserved before upgrade' WHERE id = UUID_TO_BIN(?)",
                    "30000000-0000-4000-8000-000000000001");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class)).isEqualTo(3);
            migrate(source, "schema.yml", null);
            migrate(source, "demo.yml", "demo");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notebook_shares", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT title FROM notebooks WHERE id = UUID_TO_BIN(?)",
                    String.class, "30000000-0000-4000-8000-000000000001")).isEqualTo("Preserved before upgrade");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(2);
        }
    }

    private void migrate(DriverManagerDataSource source, String path, String contexts) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(source);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.setChangeLog("classpath:db/changelog/" + path);
        liquibase.setContexts(contexts);
        liquibase.setAnalyticsEnabled(false);
        liquibase.setShowSummary(UpdateSummaryEnum.OFF);
        liquibase.afterPropertiesSet();
    }
}
