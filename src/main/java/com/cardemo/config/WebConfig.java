/*
 * WebConfig.java
 *
 * Spring MVC configuration and global exception handling for the CardDemo REST API.
 *
 * This configuration class provides three critical infrastructure components:
 * 1. CORS configuration — Enables cross-origin REST API access with observability
 *    header propagation (X-Correlation-Id).
 * 2. Jackson ObjectMapper customization — Ensures BigDecimal financial fields from
 *    COBOL COMP-3/COMP mappings serialize as plain decimal strings (never scientific
 *    notation) and java.time types serialize as ISO-8601 strings.
 * 3. Global exception handler — Maps the CardDemo exception hierarchy to appropriate
 *    HTTP status codes with structured JSON error responses, preserving COBOL FILE
 *    STATUS code traceability.
 *
 * COBOL Traceability:
 * - COBOL programs handled errors inline per program paragraph (GO TO error-paragraph,
 *   MOVE error-message TO screen field, SEND MAP). There was no centralized error
 *   handling facility in the BMS 3270 architecture.
 * - This class centralizes all REST API error handling, replacing the per-program
 *   error handling pattern with a single @ControllerAdvice.
 * - FILE STATUS codes (00, 22, 23, etc.) are preserved in the exception hierarchy
 *   and surfaced via the errorCode field in error responses.
 *
 * AAP References:
 * - §0.4.1: Spring Boot 3.5.x with Jakarta EE 10 APIs
 * - §0.7.1: Observability — correlation ID propagation in error responses
 * - §0.8.2: BigDecimal precision — no scientific notation in JSON serialization
 * - §0.8.4: Transaction/concurrency error mapping (SYNCPOINT → @Transactional)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.CommonsRequestLoggingFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC configuration class providing REST API infrastructure for the
 * CardDemo application.
 *
 * <p>Configures CORS policies and request logging. Jackson JSON serialization
 * is configured in {@link JacksonConfig} and global exception handling is
 * handled by {@link GlobalExceptionHandler}, both extracted from this class
 * for single-responsibility adherence (AAP §0.7.2 — WebConfig Decomposition).</p>
 *
 * <p>This class is part of the layered configuration refactoring that replaces
 * the per-program error handling pattern found in the original COBOL BMS 3270
 * architecture, where each of the 18 online programs handled errors inline
 * within their own paragraphs.</p>
 *
 * @see JacksonConfig
 * @see GlobalExceptionHandler
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Configurable CORS allowed origin patterns.
     *
     * <p>Defaults to {@code http://localhost:*} for local development, restricting
     * cross-origin access to localhost-based frontends. Production deployments must
     * configure specific frontend domains via the {@code CORS_ALLOWED_ORIGINS}
     * environment variable (comma-separated list) or Spring profile override.</p>
     *
     * <p>Using {@code allowedOriginPatterns} instead of {@code allowedOrigins("*")}
     * prevents cross-origin credential theft and aligns with OWASP CORS best
     * practices for API endpoints that accept authentication credentials.</p>
     */
    @org.springframework.beans.factory.annotation.Value("${carddemo.cors.allowed-origins:http://localhost:*}")
    private String corsAllowedOrigins;

    /**
     * Configures Cross-Origin Resource Sharing (CORS) for all API endpoints.
     *
     * <p>Uses {@code allowedOriginPatterns} with a configurable allowlist instead
     * of the wildcard {@code *} to prevent cross-origin credential theft attacks.
     * The allowed origins default to {@code http://localhost:*} for local development;
     * production environments must configure specific frontend domains via the
     * {@code CORS_ALLOWED_ORIGINS} environment variable or Spring profile override.</p>
     *
     * <p>Exposes the {@code X-Correlation-Id} header to enable observability
     * correlation ID propagation to API consumers (AAP §0.7.1).</p>
     *
     * <p>Replaces COBOL BMS 3270 terminal I/O, which had no cross-origin
     * concerns as all interaction was via dedicated terminal sessions.</p>
     *
     * @param registry the {@link CorsRegistry} for registering CORS mappings
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Correlation-Id");
    }

    /**
     * Creates a request/response logging filter enabled only in the local
     * development profile.
     *
     * <p>Logs request URI and query string for debugging. The payload body is
     * included for non-sensitive endpoints only. Security-sensitive endpoints
     * (authentication paths) have their payloads excluded to prevent plaintext
     * passwords from appearing in log files — per AAP §0.8.1 (no hardcoded
     * credentials or sensitive data exposure).</p>
     *
     * <p>Headers are excluded to prevent accidental logging of authentication
     * tokens (Authorization, Cookie headers).</p>
     *
     * <p>This filter is only active when {@code spring.profiles.active=local},
     * preventing verbose request logging in test, staging, or production
     * environments.</p>
     *
     * @return the configured {@link CommonsRequestLoggingFilter} that redacts
     *         payloads for authentication endpoints
     */
    @Bean
    @Profile("local")
    public CommonsRequestLoggingFilter requestLoggingFilter() {
        // Custom subclass that suppresses payload logging for auth endpoints
        // to prevent plaintext passwords from being written to log files.
        CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter() {
            @Override
            protected boolean shouldLog(jakarta.servlet.http.HttpServletRequest request) {
                return logger.isDebugEnabled();
            }

            @Override
            protected String createMessage(
                    jakarta.servlet.http.HttpServletRequest request,
                    String prefix, String suffix) {
                // Detect security-sensitive endpoints by URI path
                String uri = request.getRequestURI();
                if (uri != null && (uri.contains("/api/auth/")
                        || uri.contains("/login")
                        || uri.contains("/signin"))) {
                    // Log only the URI and method — never the request body
                    // for endpoints that accept credentials
                    StringBuilder msg = new StringBuilder();
                    msg.append(prefix);
                    msg.append(request.getMethod()).append(' ').append(uri);
                    String queryString = request.getQueryString();
                    if (queryString != null) {
                        msg.append('?').append(queryString);
                    }
                    msg.append(" [payload redacted for security]");
                    msg.append(suffix);
                    return msg.toString();
                }
                // For non-sensitive endpoints, delegate to default behavior
                return super.createMessage(request, prefix, suffix);
            }
        };
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        filter.setMaxPayloadLength(10000);
        filter.setIncludeHeaders(false);
        filter.setAfterMessagePrefix("REQUEST DATA: ");
        return filter;
    }
}
