package com.beacon.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
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
}
