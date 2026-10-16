package com.scheideckerantunes.notes.config;

import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Checks deployment settings before a datasource or migration can be initialized.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
public class DatabaseSafety {

    @Bean
    static BeanFactoryPostProcessor databaseSafetyChecks(Environment environment) {
        return beanFactory -> validate(environment);
    }

    public static void validate(Environment environment) {
        boolean protectedProfile = environment.acceptsProfiles(Profiles.of("uat | prod"));
        boolean demo = environment.getProperty("restnotes.demo.enabled", Boolean.class, false);
        String username = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");
        String migrationUser = environment.getProperty("spring.liquibase.user", "");
        if ("root".equalsIgnoreCase(username.trim()) || "root".equalsIgnoreCase(migrationUser.trim())) {
            throw new IllegalStateException("The API must not connect as the database root user");
        }
        if (demo && (protectedProfile || !environment.acceptsProfiles(Profiles.of("dev | test")))) {
            throw new IllegalStateException("Demo data is allowed only in development or test");
        }
        if (demo && !environment.getProperty("spring.liquibase.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Demo data requires enabled Liquibase migrations");
        }
        if (protectedProfile && (username.isBlank() || password.isBlank()
                || List.of("MyAPIPassword#8", "MyRootPassword#8").contains(password))) {
            throw new IllegalStateException("UAT/production require non-demo database credentials");
        }
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        if (jdbcUrl.matches("(?i).*[?&](user|password)=.*")) {
            throw new IllegalStateException("Supply database credentials separately from the JDBC URL");
        }
    }
}
