package com.cardemo.model.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * DTO capturing report generation criteria from the BMS symbolic map CORPT00.CPY
 * (report submission screen). Used by {@code ReportController.POST /api/reports/submit}.
 *
 * <p>The COBOL program CORPT00C.cbl writes report parameters to a CICS TDQ
 * (Transient Data Queue) named JOBS; in the Java migration, this triggers an
 * SQS message via {@code ReportSubmissionService} that launches a Spring Batch job.</p>
 *
 * <h3>COBOL Field Mapping (CORPT0AI input view)</h3>
 * <ul>
 *   <li>{@code monthly}  ← MONTHLYI PIC X(1) (line 60)</li>
 *   <li>{@code yearly}   ← YEARLYI  PIC X(1) (line 66)</li>
 *   <li>{@code custom}   ← CUSTOMI  PIC X(1) (line 72)</li>
 *   <li>{@code startDate} ← SDTMMI PIC X(2) + SDTDDI PIC X(2) + SDTYYYYI PIC X(4) (lines 78, 84, 90)</li>
 *   <li>{@code endDate}   ← EDTMMI PIC X(2) + EDTDDI PIC X(2) + EDTYYYYI PIC X(4) (lines 96, 102, 108)</li>
 *   <li>{@code confirm}  ← CONFIRMI PIC X(1) (line 114)</li>
 * </ul>
 *
 * <p>Rather than carrying individual month/day/year fields (as in the COBOL BMS map),
 * this DTO consolidates them into {@link LocalDate} instances for idiomatic Java
 * date handling while preserving all semantic intent.</p>
 *
 * <h3>Strict Unknown-Property Handling</h3>
 * <p>Per the QA Checkpoint 6 Info Finding #5, this DTO rejects unknown JSON
 * properties at the integration boundary. The application-wide Jackson setting
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} (configured in {@code JacksonConfig})
 * is deliberately preserved for all other DTOs to maintain API evolution
 * flexibility, but the report-submission endpoint is a write-side integration
 * boundary where silently accepting oversized or unknown payloads (observed:
 * 10&nbsp;KB unknown {@code extraField} accepted with HTTP 202) masks client-side
 * bugs and presents a minor attack surface for resource exhaustion.</p>
 *
 * <p>Because Jackson's {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * class-level annotation is a no-op when the global
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} feature is disabled, this DTO uses a
 * {@link JsonAnySetter @JsonAnySetter} method ({@link #rejectUnknownProperty})
 * which Jackson invokes for every JSON property that does not map to a declared
 * field. The method throws {@link IllegalArgumentException}, which Jackson wraps
 * in {@code JsonMappingException}, which Spring wraps in
 * {@code HttpMessageNotReadableException}, which
 * {@code GlobalExceptionHandler.handleHttpMessageNotReadableException} maps to
 * HTTP&nbsp;400. This mechanism works regardless of global Jackson feature flags
 * and provides a precise, single-DTO tightening without disturbing any other
 * API contract.</p>
 *
 * <p>This aligns the Java REST surface with the BMS 3270 terminal protocol's
 * fixed-layout semantics in the original COBOL CORPT00C.cbl (the terminal sent
 * only the defined MONTHLYI/YEARLYI/CUSTOMI/SDTMMI/SDTDDI/SDTYYYYI/EDTMMI/EDTDDI
 * /EDTYYYYI/CONFIRMI fields, with no capacity for arbitrary extension).</p>
 */
public class ReportRequest {

    // ------------------------------------------------------------------ Fields

    /**
     * Monthly report selector.
     * Maps MONTHLYI PIC X(1) from CORPT00.CPY line 60.
     * In COBOL, any non-space character in MONTHLYI means the monthly report is selected.
     */
    private boolean monthly;

    /**
     * Yearly report selector.
     * Maps YEARLYI PIC X(1) from CORPT00.CPY line 66.
     * In COBOL, any non-space character in YEARLYI means the yearly report is selected.
     */
    private boolean yearly;

    /**
     * Custom date-range report selector.
     * Maps CUSTOMI PIC X(1) from CORPT00.CPY line 72.
     * When {@code true}, {@link #startDate} and {@link #endDate} must be provided.
     */
    private boolean custom;

    /**
     * Start date for custom date-range reports.
     * Composed from SDTMMI (month), SDTDDI (day), and SDTYYYYI (year) fields
     * at CORPT00.CPY lines 78, 84, and 90 respectively.
     * Required when {@link #custom} is {@code true}; may be {@code null} otherwise.
     */
    private LocalDate startDate;

    /**
     * End date for custom date-range reports.
     * Composed from EDTMMI (month), EDTDDI (day), and EDTYYYYI (year) fields
     * at CORPT00.CPY lines 96, 102, and 108 respectively.
     * Required when {@link #custom} is {@code true}; may be {@code null} otherwise.
     */
    private LocalDate endDate;

    /**
     * Confirmation indicator.
     * Maps CONFIRMI PIC X(1) from CORPT00.CPY line 114.
     * Must be {@code "Y"} to confirm submission or {@code "N"} to cancel.
     */
    @Size(max = 1, message = "Confirm must be a single character")
    private String confirm;

    // ------------------------------------------------------------- Constructors

    /**
     * No-args constructor required for Jackson deserialization and framework
     * instantiation (e.g., Spring MVC {@code @RequestBody} binding).
     */
    public ReportRequest() {
        // Default no-args constructor
    }

    /**
     * All-args constructor for programmatic construction and test convenience.
     *
     * @param monthly   {@code true} to request a monthly report
     * @param yearly    {@code true} to request a yearly report
     * @param custom    {@code true} to request a custom date-range report
     * @param startDate start date for custom reports (nullable for monthly/yearly)
     * @param endDate   end date for custom reports (nullable for monthly/yearly)
     * @param confirm   confirmation indicator ({@code "Y"} or {@code "N"})
     */
    public ReportRequest(boolean monthly, boolean yearly, boolean custom,
                         LocalDate startDate, LocalDate endDate, String confirm) {
        this.monthly = monthly;
        this.yearly = yearly;
        this.custom = custom;
        this.startDate = startDate;
        this.endDate = endDate;
        this.confirm = confirm;
    }

    // ------------------------------------------------------ Getters and Setters

    /**
     * Returns whether a monthly report is requested.
     *
     * @return {@code true} if the monthly report type is selected
     */
    public boolean isMonthly() {
        return monthly;
    }

    /**
     * Sets the monthly report selection flag.
     *
     * @param monthly {@code true} to select the monthly report type
     */
    public void setMonthly(boolean monthly) {
        this.monthly = monthly;
    }

    /**
     * Returns whether a yearly report is requested.
     *
     * @return {@code true} if the yearly report type is selected
     */
    public boolean isYearly() {
        return yearly;
    }

    /**
     * Sets the yearly report selection flag.
     *
     * @param yearly {@code true} to select the yearly report type
     */
    public void setYearly(boolean yearly) {
        this.yearly = yearly;
    }

    /**
     * Returns whether a custom date-range report is requested.
     *
     * @return {@code true} if the custom report type is selected
     */
    public boolean isCustom() {
        return custom;
    }

    /**
     * Sets the custom date-range report selection flag.
     *
     * @param custom {@code true} to select the custom date-range report type
     */
    public void setCustom(boolean custom) {
        this.custom = custom;
    }

    /**
     * Returns the start date for a custom date-range report.
     *
     * @return the start date, or {@code null} if not a custom report
     */
    public LocalDate getStartDate() {
        return startDate;
    }

    /**
     * Sets the start date for a custom date-range report.
     *
     * @param startDate the start date for the custom range
     */
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    /**
     * Returns the end date for a custom date-range report.
     *
     * @return the end date, or {@code null} if not a custom report
     */
    public LocalDate getEndDate() {
        return endDate;
    }

    /**
     * Sets the end date for a custom date-range report.
     *
     * @param endDate the end date for the custom range
     */
    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    /**
     * Returns the confirmation indicator.
     *
     * @return the confirmation string ({@code "Y"} or {@code "N"})
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets the confirmation indicator.
     *
     * @param confirm the confirmation value ({@code "Y"} to confirm, {@code "N"} to cancel)
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    // ------------------------------------------------------ Cross-Field Validation

    /**
     * Cross-field validation ensuring the three report type flags are mutually
     * exclusive — at most one of {@link #monthly}, {@link #yearly}, or
     * {@link #custom} may be {@code true} in a single request.
     *
     * <p>Per AAP §0.3.1 and the QA Checkpoint 6 Minor Finding #4, previously
     * {@code ReportSubmissionService} silently honored a priority chain
     * ({@code monthly} → {@code yearly} → {@code custom}), so clients sending
     * {@code {"monthly":true,"yearly":true}} received a Monthly report without
     * any indication of the ambiguity. This constraint rejects such requests at
     * the web layer with an HTTP 400 response (via
     * {@code GlobalExceptionHandler.handleMethodArgumentNotValidException}), which
     * matches the explicit single-selection BMS map semantics of the original
     * COBOL program CORPT00C.cbl (only one of MONTHLYI/YEARLYI/CUSTOMI carries a
     * non-space character in a valid terminal interaction).</p>
     *
     * <p>A request where <em>none</em> of the flags are set is <strong>not</strong>
     * rejected here — that case is handled downstream by
     * {@code ReportSubmissionService} which raises a domain
     * {@link com.cardemo.exception.ValidationException} with the COBOL-equivalent
     * message {@code "Select a report type to print report..."}, preserving the
     * original error channel per R-001 behavioral parity.</p>
     *
     * <p>{@link JsonIgnore} is applied so Jackson does not treat this boolean
     * accessor as a serializable/deserializable property — the method exists
     * purely for Jakarta Bean Validation introspection.</p>
     *
     * @return {@code true} when at most one report type flag is {@code true}
     *         (the valid state); {@code false} when two or more flags are
     *         {@code true}, which triggers Jakarta Bean Validation failure
     */
    @AssertTrue(message = "Exactly one of monthly, yearly, or custom may be selected")
    @JsonIgnore
    public boolean isReportTypeExclusive() {
        int count = (monthly ? 1 : 0) + (yearly ? 1 : 0) + (custom ? 1 : 0);
        return count <= 1;
    }

    // ------------------------------------------------ Unknown-property rejection

    /**
     * Rejects any JSON property that does not map to a declared field.
     *
     * <p>Jackson invokes this {@link JsonAnySetter @JsonAnySetter}-annotated
     * method for every inbound JSON property for which no regular setter is
     * available. By throwing {@link IllegalArgumentException}, this method
     * causes Jackson to fail the entire deserialization attempt with
     * {@code JsonMappingException}, which Spring Web wraps in
     * {@code HttpMessageNotReadableException}. The existing
     * {@code GlobalExceptionHandler.handleHttpMessageNotReadableException}
     * handler then returns HTTP&nbsp;400 to the client.</p>
     *
     * <p>Allowed field names: {@code monthly}, {@code yearly}, {@code custom},
     * {@code startDate}, {@code endDate}, {@code confirm}. Any other key in
     * the request body is rejected. This is the QA Checkpoint 6 Info #5 fix:
     * the previously-suggested {@code @JsonIgnoreProperties(ignoreUnknown = false)}
     * class-level annotation is a no-op when Jackson's global
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} feature is disabled, so an
     * {@code @JsonAnySetter} catch-all is the only reliable per-DTO mechanism
     * that does not require flipping the global feature (which would require
     * auditing every other DTO in the codebase for unrelated client payload
     * compatibility).</p>
     *
     * @param key   the unrecognized JSON property name as sent by the client
     * @param value the property value, ignored since the request is rejected
     * @throws IllegalArgumentException always — the method exists solely to
     *         signal unknown-property failure to Jackson
     */
    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new IllegalArgumentException(
                "Unknown property '" + key + "' is not allowed in ReportRequest. "
                        + "Allowed properties: monthly, yearly, custom, startDate, endDate, confirm.");
    }

    // ---------------------------------------------------------------- toString

    /**
     * Returns a string representation of this report request, including all fields.
     * Useful for logging and diagnostics.
     *
     * @return a descriptive string containing all field values
     */
    @Override
    public String toString() {
        return "ReportRequest{" +
                "monthly=" + monthly +
                ", yearly=" + yearly +
                ", custom=" + custom +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", confirm='" + confirm + '\'' +
                '}';
    }
}
