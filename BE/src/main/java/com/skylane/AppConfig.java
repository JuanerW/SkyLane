package com.skylane;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skylane.auth.JwtAuthFilter;
import com.skylane.auth.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableScheduling
public class AppConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${skylane.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key", "Last-Event-ID"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ObjectProvider<JdbcTemplate> jdbc,
                                            Environment env, ObjectMapper mapper) throws Exception {
        // The 'memory' profile has no database, hence no users: its quote endpoints stay open with a fake user.
        boolean memory = env.acceptsProfiles(Profiles.of("memory"));
        JdbcTemplate template = jdbc.getIfAvailable();
        if (template != null) {
            http.addFilterBefore(new JwtAuthFilter(jwt, template), UsernamePasswordAuthenticationFilter.class);
        }
        return http
                .cors(c -> {})
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(res.getOutputStream(), Map.of("error",
                            Map.of("code", "UNAUTHORIZED", "message", "Login required", "details", Map.of())));
                }).accessDeniedHandler((req, res, ex) -> {
                    // Design 8.1: a customer calling /ops or /admin gets 403 FORBIDDEN.
                    res.setStatus(403);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    mapper.writeValue(res.getOutputStream(), Map.of("error",
                            Map.of("code", "FORBIDDEN", "message", "You do not have access to this page", "details", Map.of())));
                }))
                .authorizeHttpRequests(auth -> {
                    // SSE responses finish on an async dispatch; the original request was already authorized.
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/v1/auth/**", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                            .permitAll();
                    // The public home page shows the stations before login (design 8.5).
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/stations").permitAll();
                    if (memory) {
                        auth.requestMatchers("/api/v1/quotes").permitAll();
                    }
                    auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/ops/**").hasAnyRole("OPERATOR", "ADMIN");
                    auth.anyRequest().authenticated();
                })
                .build();
    }
}
