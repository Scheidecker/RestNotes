package com.scheideckerantunes.notes.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Fail-closed foundation policy; real user authentication is introduced in Phase 3.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean swaggerUiEnabled) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(
                            "/actuator/health", "/actuator/health/liveness",
                            "/actuator/health/readiness", "/actuator/info").permitAll();
                    if (apiDocsEnabled) {
                        requests.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll();
                    }
                    if (swaggerUiEnabled) {
                        requests.requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    requests.anyRequest().denyAll();
                })
                .build();
    }

    @Bean
    UserDetailsService unavailableUserDetailsService() {
        // Prevent auto-generated demo credentials before persistence-backed authentication exists.
        return username -> {
            throw new UsernameNotFoundException("User authentication is not available in Phase 1");
        };
    }
}
