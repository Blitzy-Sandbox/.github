/*
 * JacksonConfig.java
 *
 * Jackson ObjectMapper customization for the CardDemo REST API.
 *
 * This configuration class centralizes Jackson serialization/deserialization
 * behavior for the application. It was extracted from WebConfig.java to follow
 * the single-responsibility principle (AAP §0.7.2 — WebConfig Decomposition).
 *
 * COBOL Traceability:
 * - COBOL programs used fixed PIC clauses (e.g., PIC S9(9)V99 COMP-3) that
 *   preserved exact decimal precision at the data-type level.
 * - The REST API counterpart uses java.math.BigDecimal to preserve precision,
 *   and this Jackson customization ensures BigDecimal serializes as plain
 *   decimal text (never scientific notation) so the wire format matches the
 *   COBOL COMP-3 semantic.
 *
 * AAP References:
 * - §0.4.1: Spring Boot 3.5.x with Jakarta EE 10 APIs
 * - §0.7.2: WebConfig Decomposition — extracted Jackson customization
 * - §0.8.2: BigDecimal precision — no scientific notation in JSON serialization
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class providing Jackson ObjectMapper customization
 * for the CardDemo REST API.
 *
 * <p>This class was extracted from {@link WebConfig} as part of the refactoring
 * effort to separate concerns within the configuration layer (AAP §0.7.2).
 * The original WebConfig mixed CORS configuration, Jackson customization, and
 * global exception handling in a single 840-line class.</p>
 *
 * <p>The Jackson customization defined here is critical for COBOL-to-Java data
 * precision preservation, ensuring that BigDecimal financial values serialize
 * as plain decimal strings and java.time types serialize as ISO-8601 strings.</p>
 *
 * @see WebConfig
 * @see GlobalExceptionHandler
 */
@Configuration
public class JacksonConfig {

    /**
     * Customizes the Jackson {@code ObjectMapper} for CardDemo-specific
     * serialization requirements.
     *
     * <p>Critical configuration for COBOL-to-Java data precision preservation:</p>
     * <ul>
     *   <li>{@code JavaTimeModule} — Enables ISO-8601 date serialization for
     *       {@code java.time} types, replacing COBOL LE CEEDAYS date handling</li>
     *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=false} — Produces human-readable
     *       ISO-8601 strings instead of numeric epoch timestamps</li>
     *   <li>{@code WRITE_BIGDECIMAL_AS_PLAIN=true} — Ensures all BigDecimal
     *       financial fields from COBOL COMP-3/COMP mappings serialize as plain
     *       decimal strings (e.g., "1234.56") instead of scientific notation
     *       (e.g., "1.23456E3") per AAP §0.8.2</li>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — Permissive deserialization
     *       for forward compatibility with evolving API contracts</li>
     *   <li>{@code NON_NULL} inclusion — Excludes null fields from JSON output,
     *       reducing payload size and producing cleaner API responses</li>
     * </ul>
     *
     * @return the Jackson customizer bean for Spring Boot auto-configuration
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modulesToInstall(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToEnable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
