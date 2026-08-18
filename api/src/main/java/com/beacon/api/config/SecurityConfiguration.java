package com.beacon.api.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Registration must be reachable without an account.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        // Ambient city conditions and map tiles describe the
                        // environment, not a person, so they stay public.
                        .requestMatchers(
                                "/api/v1/conditions/**",
                                "/api/v1/hazard-fields/**",
                                "/api/v1/profiles/preview",
                                "/api/v1/tiles/**")
                        .permitAll()
                        // Routing itself is public so the planner works before
                        // signing up, but anything that reads back a stored
                        // route belongs to whoever created it.
                        .requestMatchers(HttpMethod.POST, "/api/v1/routes").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/routes/compare").permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Cross-origin access, off unless origins are named.
     *
     * <p>A deployment that proxies the API under the web app's own domain needs
     * none of this and should not have it. It exists for the case where the
     * frontend is served from a different host than the API.
     *
     * <p>Origins must be listed explicitly. Credentials are allowed because
     * authentication is HTTP Basic, and a wildcard origin with credentials is
     * both refused by browsers and a bad idea: it would let any site issue
     * authenticated requests on a visitor's behalf.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${beacon.cors.allowed-origins:}") String allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            return source;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept",
                "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(java.time.Duration.ofMinutes(30));
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
