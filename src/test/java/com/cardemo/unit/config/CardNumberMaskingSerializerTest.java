/*
 * CardNumberMaskingSerializerTest.java
 *
 * Unit tests for {@link CardNumberMaskingSerializer} that validate the
 * PCI-DSS 3.3 / 3.4 masking behavior required by QA Finding #1.
 *
 * Coverage:
 *  - Null input delegates to default null serialization
 *  - Short strings (length less than 13) emitted unchanged
 *  - 13-digit PAN boundary case (minimum ISO/IEC 7812 length)
 *  - Standard 16-digit PAN masking
 *  - 15-digit Amex PAN masking
 *  - 19-digit PAN masking (ISO/IEC 7812 upper bound)
 *  - End-to-end Jackson serialization of DTOs referencing this serializer
 *  - Matching of the checkpoint regex {@code ^[X\*]{12}[0-9]{4}$} for 16-digit PANs
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.config;

import com.cardemo.config.CardNumberMaskingSerializer;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.TransactionDto;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies PCI-DSS compliance of the {@link CardNumberMaskingSerializer}.
 *
 * <p>The serializer must replace all but the final four characters of any
 * card-number-length string with {@code '*'} while preserving shorter
 * placeholders (e.g., empty strings, {@code "N/A"}) unchanged.</p>
 */
@DisplayName("CardNumberMaskingSerializer (QA Finding #1 — PCI-DSS)")
class CardNumberMaskingSerializerTest {

    private final CardNumberMaskingSerializer serializer = new CardNumberMaskingSerializer();

    // -----------------------------------------------------------------------
    // Direct serialize(...) behavior
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("16-digit PAN is masked to 12 asterisks + last 4 digits")
    void masks16DigitPan() throws IOException {
        String result = writeValue("4859452612877065");
        assertThat(result).isEqualTo("\"************7065\"");
        // Verify the checkpoint regex ^[X\*]{12}[0-9]{4}$
        String raw = unwrap(result);
        assertThat(raw).matches("^\\*{12}[0-9]{4}$");
    }

    @Test
    @DisplayName("15-digit Amex PAN is masked to 11 asterisks + last 4 digits")
    void masks15DigitAmexPan() throws IOException {
        String result = writeValue("371449635398431");
        assertThat(unwrap(result)).isEqualTo("***********8431");
        assertThat(unwrap(result)).hasSize(15);
    }

    @Test
    @DisplayName("13-digit PAN (minimum ISO/IEC 7812) is masked")
    void masks13DigitMinimumPan() throws IOException {
        String result = writeValue("1234567890123");
        assertThat(unwrap(result)).isEqualTo("*********0123");
        assertThat(unwrap(result)).hasSize(13);
    }

    @Test
    @DisplayName("19-digit PAN (maximum ISO/IEC 7812) is masked")
    void masks19DigitMaximumPan() throws IOException {
        String result = writeValue("1234567890123456789");
        assertThat(unwrap(result)).isEqualTo("***************6789");
        assertThat(unwrap(result)).hasSize(19);
    }

    @Test
    @DisplayName("12-character string (below PAN minimum) is emitted unchanged")
    void doesNotMaskStringShorterThanMinimumPan() throws IOException {
        String result = writeValue("123456789012");
        assertThat(unwrap(result)).isEqualTo("123456789012");
    }

    @Test
    @DisplayName("Empty string is emitted unchanged")
    void emitsEmptyStringUnchanged() throws IOException {
        String result = writeValue("");
        assertThat(unwrap(result)).isEqualTo("");
    }

    @Test
    @DisplayName("Short placeholder like 'N/A' is emitted unchanged")
    void emitsShortPlaceholderUnchanged() throws IOException {
        String result = writeValue("N/A");
        assertThat(unwrap(result)).isEqualTo("N/A");
    }

    @Test
    @DisplayName("Null value delegates to SerializerProvider.defaultSerializeNull")
    void nullValueDelegatesToDefaultNullSerialization() throws IOException {
        JsonGenerator gen = mock(JsonGenerator.class);
        SerializerProvider provider = mock(SerializerProvider.class);

        serializer.serialize(null, gen, provider);

        verify(provider).defaultSerializeNull(gen);
    }

    // -----------------------------------------------------------------------
    // End-to-end DTO serialization (the public contract of the change)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("CardDto.cardNum is masked when serialized through an ObjectMapper")
    void cardDtoMasksCardNumInJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        CardDto dto = new CardDto();
        dto.setCardNum("4859452612877065");
        dto.setCardEmbossedName("JOHN SMITH");
        dto.setCardAcctId("00000000010");
        dto.setCardActiveStatus("Y");

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .as("Response MUST mask the full PAN per PCI-DSS 3.3")
                .contains("\"cardNum\":\"************7065\"")
                .doesNotContain("4859452612877065");
    }

    @Test
    @DisplayName("CardDto.cardCvvCd is omitted from outbound JSON (@JsonProperty WRITE_ONLY)")
    void cardDtoOmitsCvvInJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        CardDto dto = new CardDto();
        dto.setCardNum("4859452612877065");
        dto.setCardCvvCd("123");

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .as("CVV MUST never appear in any outbound JSON response (PCI-DSS 3.2)")
                .doesNotContain("cardCvvCd")
                .doesNotContain("\"123\"");
    }

    @Test
    @DisplayName("CardDto.cardCvvCd is ACCEPTED on inbound JSON deserialization (@JsonProperty WRITE_ONLY)")
    void cardDtoAcceptsCvvOnInboundDeserialization() throws Exception {
        // This test guards against regressing to @JsonIgnore, which would
        // bidirectionally strip the field and break PUT/POST flows that must
        // persist CVV to the NOT NULL card_cvv_cd column. @JsonProperty(access
        // = WRITE_ONLY) must permit inbound values while still suppressing
        // them in outbound serialization.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        String inboundJson = "{"
                + "\"cardNum\":\"4859452612877065\","
                + "\"cardAcctId\":\"00000000050\","
                + "\"cardEmbossedName\":\"Aniya Von UPDATED\","
                + "\"cardActiveStatus\":\"Y\","
                + "\"cardCvvCd\":\"747\","
                + "\"version\":0"
                + "}";

        CardDto deserialized = mapper.readValue(inboundJson, CardDto.class);

        assertThat(deserialized.getCardCvvCd())
                .as("CVV MUST be accepted on inbound deserialization for PUT/POST persistence")
                .isEqualTo("747");
        assertThat(deserialized.getCardNum())
                .as("cardNum round-trips through deserialization")
                .isEqualTo("4859452612877065");
    }

    @Test
    @DisplayName("TransactionDto.tranCardNum is masked when serialized through an ObjectMapper")
    void transactionDtoMasksCardNumberInJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        TransactionDto dto = new TransactionDto();
        dto.setTranId("0000000000000001");
        dto.setTranCardNum("4859452612877065");
        dto.setTranAmt(new BigDecimal("100.00"));
        dto.setTranTypeCd("01");
        dto.setTranCatCd("0001");
        dto.setTranSource("POS TERM");
        dto.setTranDesc("TEST TRANSACTION");
        dto.setTranMerchId("000000123");
        dto.setTranMerchName("TEST MERCHANT");
        dto.setTranMerchCity("SEATTLE");
        dto.setTranMerchZip("98101");
        dto.setTranOrigTs(LocalDateTime.now());
        dto.setTranProcTs(LocalDateTime.now());

        String json = mapper.writeValueAsString(dto);

        assertThat(json)
                .as("Response MUST mask the full PAN per PCI-DSS 3.3")
                .contains("\"tranCardNum\":\"************7065\"")
                .doesNotContain("4859452612877065");
    }

    @Test
    @DisplayName("CardDto round-trip JSON matches checkpoint regex ^[X\\*]{12}[0-9]{4}$")
    void cardDtoMaskingMatchesCheckpointRegex() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        CardDto dto = new CardDto();
        dto.setCardNum("4111111111111111");

        String json = mapper.writeValueAsString(dto);

        // Extract the cardNum value from the JSON
        int start = json.indexOf("\"cardNum\":\"") + "\"cardNum\":\"".length();
        int end = json.indexOf('"', start);
        String masked = json.substring(start, end);

        assertThat(masked)
                .as("Masked PAN must match checkpoint regex ^[X\\*]{12}[0-9]{4}$")
                .matches("^\\*{12}[0-9]{4}$");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Serializes {@code value} through a real Jackson {@link ObjectMapper}
     * configured with a module that applies the serializer under test to
     * all strings. Returns the raw JSON (including surrounding quotes for
     * non-null strings).
     */
    private String writeValue(String value) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, serializer);
        mapper.registerModule(module);

        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, value);
        return writer.toString();
    }

    /**
     * Strips surrounding double-quotes from a JSON string value.
     */
    private String unwrap(String jsonString) {
        if (jsonString.length() >= 2 && jsonString.startsWith("\"") && jsonString.endsWith("\"")) {
            return jsonString.substring(1, jsonString.length() - 1);
        }
        return jsonString;
    }
}
