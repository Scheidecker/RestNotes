package com.scheideckerantunes.notes;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scheideckerantunes.notes.config.DatabaseSafety;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Proves unsafe configuration fails before any database connection is attempted.
 *
 * @author Carlos Scheidecker
 */
class DatabaseSafetyTest {

    @Test
    void rejectsMissingAndDemoCredentialsInProtectedProfiles() {
        for (String profile : new String[] {"uat", "prod"}) {
            MockEnvironment env = environment(profile);
            assertThatThrownBy(() -> DatabaseSafety.validate(env)).isInstanceOf(IllegalStateException.class);
            env.setProperty("spring.datasource.password", "MyAPIPassword#8");
            assertThatThrownBy(() -> DatabaseSafety.validate(env)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsDemoWithMixedProductionProfiles() {
        MockEnvironment env = environment("dev", "prod");
        env.setProperty("spring.datasource.password", UUID.randomUUID().toString());
        env.setProperty("restnotes.demo.enabled", "true");
        assertThatThrownBy(() -> DatabaseSafety.validate(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Demo data");
    }

    @Test
    void rejectsRootRegardlessOfProfile() {
        MockEnvironment env = environment("dev");
        env.setProperty("spring.datasource.username", "root");
        assertThatThrownBy(() -> DatabaseSafety.validate(env)).hasMessageContaining("root");
        env.setProperty("spring.datasource.username", "APIUser");
        env.setProperty("spring.liquibase.user", "root");
        assertThatThrownBy(() -> DatabaseSafety.validate(env)).hasMessageContaining("root");
    }

    @Test
    void demoRequiresSupportedProfileAndEnabledMigrations() {
        MockEnvironment env = environment("dev");
        env.setProperty("restnotes.demo.enabled", "true");
        env.setProperty("spring.liquibase.enabled", "false");
        assertThatThrownBy(() -> DatabaseSafety.validate(env)).hasMessageContaining("requires");
        env.setProperty("spring.liquibase.enabled", "true");
        assertThatCode(() -> DatabaseSafety.validate(env)).doesNotThrowAnyException();
        env.setActiveProfiles("custom");
        assertThatThrownBy(() -> DatabaseSafety.validate(env)).hasMessageContaining("Demo data");
    }

    @Test
    void acceptsIndependentProtectedCredentialsWithoutPrintingThem() {
        MockEnvironment env = environment("prod");
        env.setProperty("spring.datasource.password", UUID.randomUUID().toString());
        assertThatCode(() -> DatabaseSafety.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void rejectsCredentialsEmbeddedInJdbcUrl() {
        MockEnvironment env = environment("dev");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost/RestNotes?user=APIUser");
        assertThatThrownBy(() -> DatabaseSafety.validate(env)).hasMessageContaining("separately");
    }

    private MockEnvironment environment(String... profiles) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.username", "APIUser")
                .withProperty("spring.datasource.password", "");
        env.setActiveProfiles(profiles);
        return env;
    }
}
