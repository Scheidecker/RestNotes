package com.scheideckerantunes.notes.config;

import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Always selects a schema-only entry point unless demo loading is explicitly enabled.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {

    @Bean
    static BeanPostProcessor migrationEntryPoint(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof SpringLiquibase liquibase) {
                    DatabaseSafety.validate(environment);
                    boolean demo = environment.getProperty("restnotes.demo.enabled", Boolean.class, false);
                    liquibase.setChangeLog(demo
                            ? "classpath:db/changelog/demo.yml"
                            : "classpath:db/changelog/schema.yml");
                    liquibase.setContexts(demo ? "demo" : "schema");
                }
                return bean;
            }
        };
    }
}
