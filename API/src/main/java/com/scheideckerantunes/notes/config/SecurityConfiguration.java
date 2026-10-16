package com.scheideckerantunes.notes.config;
import com.scheideckerantunes.notes.auth.EmailAddress;
import com.scheideckerantunes.notes.common.CorrelationIdFilter;
import com.scheideckerantunes.notes.common.SecurityProblems;
import com.scheideckerantunes.notes.user.UserAccountRepository;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Header-only bearer authentication, with default-deny for unimplemented business routes.
 *
 * @author Carlos Scheidecker
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProblems problems,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled,
            @Value("${springdoc.swagger-ui.enabled:false}") boolean swaggerUiEnabled) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .addFilterBefore(new CorrelationIdFilter(), SecurityContextHolderFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(problems).accessDeniedHandler(problems)
                        .jwt(jwt -> {
                            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
                            converter.setJwtGrantedAuthoritiesConverter(token -> List.of());
                            jwt.jwtAuthenticationConverter(converter);
                        }))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.POST,
                            "/api/v1/auth/register", "/api/v1/auth/login").permitAll();
                    requests.requestMatchers(HttpMethod.GET, "/api/v1/users/me", "/api/v1/users/me/teams")
                            .authenticated();
                    requests.requestMatchers(HttpMethod.GET,
                            "/api/v1/teams", "/api/v1/teams/*", "/api/v1/teams/*/members").authenticated();
                    requests.requestMatchers(HttpMethod.POST,
                            "/api/v1/teams", "/api/v1/teams/*/members").authenticated();
                    requests.requestMatchers(HttpMethod.PATCH, "/api/v1/teams/*/members/*").authenticated();
                    requests.requestMatchers(HttpMethod.DELETE, "/api/v1/teams/*/members/*").authenticated();
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
    UserDetailsService userDetailsService(UserAccountRepository users) {
        return email -> users.findByEmail(EmailAddress.normalize(email))
                .map(account -> User.withUsername(account.getId().toString())
                        .password(account.getPasswordHash()).authorities(List.of()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean
    PasswordEncoder passwordEncoder(@Value("${restnotes.security.bcrypt-strength:10}") int strength) {
        if (strength < 10 || strength > 14) {
            throw new IllegalStateException("BCrypt strength must be between 10 and 14");
        }
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }
}
