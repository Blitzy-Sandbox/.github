/*
 * CardNumberMaskingSerializer.java
 *
 * Custom Jackson serializer that masks Primary Account Numbers (PANs) in
 * outbound JSON responses to comply with the PCI-DSS 3.3 / 3.4 mandate
 * that full PANs must never be returned in clear to API consumers.
 *
 * Masking Policy:
 * - Input length less than 13  -> returned unchanged (not a PAN)
 * - Input length 13 or more    -> first (length - 4) characters replaced with
 *                                 '*' and the last 4 characters preserved
 *   Example: "4859452612877065" -> "************7065"
 *
 * This serializer is applied to every DTO field that carries a full PAN,
 * regardless of the endpoint or the role of the caller. CVV codes are
 * removed from outbound responses entirely via @JsonIgnore — they are
 * never masked because they must never appear in any read response.
 *
 * COBOL Traceability:
 * - COBOL source programs (COCRDSLC.cbl, COTRN01C.cbl, COTRN02C.cbl)
 *   rendered the full card number on terminal screens. PCI-DSS is a
 *   regulatory requirement that post-dates the mainframe programs and
 *   applies to the modernized REST API layer, not to the COBOL parity
 *   contract — the on-wire JSON field name remains unchanged, only the
 *   field value is masked.
 *
 * AAP References:
 * - §0.8.1 R-006: API contract stability (field names, HTTP status codes
 *   unchanged; only the value shape is masked)
 * - §0.8.1 R-001: Behavioral parity preserved — all business logic,
 *   validation, and storage operations continue to use the full PAN;
 *   masking occurs only at the JSON serialization boundary.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * Jackson {@code JsonSerializer} that replaces all but the final four
 * characters of a card number with {@code '*'} for outbound JSON
 * serialization.
 *
 * <p>The serializer is stateless and thread-safe; a single shared instance
 * may be referenced from multiple DTO fields via
 * {@code @JsonSerialize(using = CardNumberMaskingSerializer.class)}.</p>
 *
 * <p>Null values are delegated to
 * {@link SerializerProvider#defaultSerializeNull(JsonGenerator)} so that
 * downstream inclusion rules (e.g., {@code JsonInclude.Include.NON_NULL})
 * continue to apply.</p>
 *
 * <h3>Masking Examples</h3>
 * <pre>
 *   "4859452612877065" -> "************7065"  (full 16-digit PAN)
 *   "371449635398431"  -> "***********8431"   (15-digit Amex PAN)
 *   "1234567890123"    -> "*********0123"     (13-digit PAN minimum)
 *   "1234"             -> "1234"              (not a PAN, returned unchanged)
 *   ""                 -> ""                  (empty string returned unchanged)
 *   null               -> handled via default null serialization
 * </pre>
 *
 * @see CardDemoApplication
 * @see com.cardemo.model.dto.CardDto
 * @see com.cardemo.model.dto.TransactionDto
 */
public class CardNumberMaskingSerializer extends StdSerializer<String> {

    /**
     * Serialization version UID for {@link java.io.Serializable} contract
     * inherited via {@link StdSerializer}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The number of trailing digits preserved in clear after masking.
     * PCI-DSS 3.3 permits display of no more than the first six and last
     * four digits of the PAN; this implementation preserves only the
     * last four per the stricter interpretation.
     */
    private static final int VISIBLE_TRAILING_DIGITS = 4;

    /**
     * Minimum length at which a value is treated as a card number and
     * therefore masked. Values shorter than this are emitted unchanged
     * because they cannot be valid ISO/IEC 7812 PANs (13–19 digits).
     */
    private static final int MIN_PAN_LENGTH = 13;

    /**
     * The mask character used to replace all but the last four characters
     * of a PAN. Matches the masking regex {@code ^[X\\*]{12}[0-9]{4}$}
     * documented in the checkpoint-4 PCI verification requirement.
     */
    private static final char MASK_CHAR = '*';

    /**
     * No-argument constructor required by Jackson reflection when the
     * serializer is referenced via {@code @JsonSerialize(using = ...)}.
     */
    public CardNumberMaskingSerializer() {
        super(String.class);
    }

    /**
     * Serializes the supplied {@code value} as a JSON string, masking
     * all but the final four characters when the value is long enough
     * to be a card number.
     *
     * @param value       the card number string (nullable; may be shorter
     *                    than a PAN, in which case it is emitted unchanged)
     * @param gen         the Jackson {@link JsonGenerator} that receives
     *                    the masked output
     * @param serializers the current {@link SerializerProvider}, used to
     *                    delegate null serialization
     * @throws IOException if the underlying generator fails to write
     */
    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null) {
            serializers.defaultSerializeNull(gen);
            return;
        }
        if (value.length() < MIN_PAN_LENGTH) {
            // Not a PAN (or a placeholder such as "N/A") — emit unchanged.
            gen.writeString(value);
            return;
        }
        gen.writeString(mask(value));
    }

    /**
     * Builds the masked representation of the supplied PAN.
     *
     * <p>Package-private for unit testing; the public serialization
     * contract is exposed via {@link #serialize(String, JsonGenerator,
     * SerializerProvider)}.</p>
     *
     * @param pan a non-null card number of length
     *            {@value #MIN_PAN_LENGTH} or greater
     * @return the masked representation with all but the last four
     *         characters replaced by {@value #MASK_CHAR}
     */
    static String mask(String pan) {
        int maskedLength = pan.length() - VISIBLE_TRAILING_DIGITS;
        StringBuilder builder = new StringBuilder(pan.length());
        for (int i = 0; i < maskedLength; i++) {
            builder.append(MASK_CHAR);
        }
        builder.append(pan, maskedLength, pan.length());
        return builder.toString();
    }
}
