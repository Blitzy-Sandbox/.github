package com.cardemo.domain.constants;

import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Set;

/**
 * Centralized constant holder for date/time formatting patterns used across
 * the CardDemo service and batch layers.
 *
 * <p>Consolidates {@link DateTimeFormatter} instances and supporting date
 * validation constants that were previously duplicated across 11+ files
 * (service account/transaction/report, batch jobs/readers/writers, and the
 * shared date validation service). This class follows the Constant Holder
 * Pattern described in AAP &sect;0.4.3 &mdash; a {@code public final} class
 * with a {@code private} no-argument constructor to prevent instantiation
 * and subclassing.</p>
 *
 * <h3>COBOL Source Traceability</h3>
 * <p>Date formats map directly to COBOL PIC clauses. The canonical eight-digit
 * date representation {@code CCYYMMDD} (century + year + month + day) originates
 * from the COBOL service program {@code CSUTLDTC.cbl} (date validation
 * subprogram) and the copybook {@code CSUTLDPY.cpy}. Timestamp formats track
 * {@code PIC X(26)} conventions for {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}
 * fields in {@code CVTRA05Y.cpy}. The supporting integer and string constants
 * mirror the COBOL {@code CSUTLDWY.cpy} working-storage section (lines 9-57)
 * including {@code LAST-CENTURY}, {@code THIS-CENTURY}, {@code WS-FEBRUARY},
 * and the severity-0 {@code CEE000} feedback code text.</p>
 *
 * <h3>Consuming Classes (post-refactoring)</h3>
 * <p>All service classes that format or parse dates reference these constants,
 * including {@code AccountUpdateService}, {@code TransactionAddService},
 * {@code DateValidationService}, {@code ReportSubmissionService}, and every
 * batch job, reader, processor, and writer that emits or consumes CCYYMMDD
 * dates or COBOL PIC X(26) timestamps (e.g., {@code CombineTransactionsJob},
 * {@code InterestCalculationJob}, {@code TransactionReportJob},
 * {@code DailyTransactionReader}, {@code RejectWriter},
 * {@code StatementWriter}, {@code TransactionWriter}).</p>
 *
 * <h3>Thread Safety</h3>
 * <p>{@link DateTimeFormatter} instances are immutable and thread-safe once
 * configured, making them safe to share as {@code public static final}
 * singletons without synchronization. The {@link Set} returned by
 * {@link Set#of(Object, Object, Object, Object, Object, Object, Object)} is
 * likewise unmodifiable and thread-safe. All primitive and {@link String}
 * constants are immutable by language contract.</p>
 *
 * @see com.cardemo.service.shared.DateValidationService
 * @see com.cardemo.service.account.AccountUpdateService
 * @see com.cardemo.service.transaction.TransactionAddService
 * @see com.cardemo.service.report.ReportSubmissionService
 */
public final class DateFormatConstants {

    /**
     * CCYYMMDD date formatter using {@code "yyyyMMdd"} (lenient resolver).
     *
     * <p>Matches the standard COBOL eight-digit date literal format used by
     * {@code AccountUpdateService} and {@code TransactionAddService} for
     * parsing open/expiry/reissue date fields. Resolver style is SMART
     * (the default) &mdash; differs from {@link #CCYYMMDD_STRICT_FORMATTER}
     * which uses {@link ResolverStyle#STRICT} for rejecting out-of-range
     * days (e.g., February 29 in a non-leap year).</p>
     *
     * <p>COBOL source traceability: mirrors the {@code PIC 9(08)} open-date,
     * expiry-date, and reissue-date fields defined in copybook
     * {@code CVACT01Y.cpy} and consumed by the online account update program
     * {@code COACTUPC.cbl} paragraph {@code 1200-EDIT-MAP-INPUTS}.</p>
     */
    public static final DateTimeFormatter CCYYMMDD_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * CCYYMMDD date formatter using {@code "uuuuMMdd"} with STRICT resolver.
     *
     * <p>Used by {@link com.cardemo.service.shared.DateValidationService} to
     * emulate the COBOL {@code CEEDAYS} service's rejection semantics for
     * invalid dates (for example, February 29 in a non-leap year). The
     * {@code uuuu} year-of-era pattern combined with
     * {@link ResolverStyle#STRICT} causes any out-of-range day-of-month to
     * fail parsing rather than being silently adjusted by the default SMART
     * resolver.</p>
     *
     * <p>COBOL source traceability: replaces the LE {@code CEEDAYS} API call
     * in {@code CSUTLDTC.cbl} (paragraph {@code EDIT-DATE-LE}, lines 284-331
     * of {@code CSUTLDPY.cpy}).</p>
     */
    public static final DateTimeFormatter CCYYMMDD_STRICT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Timestamp formatter for generating unique S3 object keys during backup
     * operations: {@code "yyyyMMddHHmmss"}.
     *
     * <p>Used by {@code CombineTransactionsJob}, {@code InterestCalculationJob},
     * {@code TransactionReportJob}, {@code RejectWriter}, {@code StatementWriter},
     * and {@code TransactionWriter} to stamp backup S3 object keys with a
     * second-precision timestamp (14 digits, no separators).</p>
     *
     * <p>COBOL source traceability: replaces the GDG generation numbering
     * convention (e.g., {@code TRANSACT.COMBINED(+1)}, {@code DALYREJS(+1)})
     * with a timestamp-based S3 key suffix. Historically generated via the
     * COBOL {@code FUNCTION CURRENT-DATE} intrinsic used in
     * {@code CBSTM03A.cbl} for GDG dataset naming.</p>
     *
     * @see com.cardemo.batch.jobs.CombineTransactionsJob
     * @see com.cardemo.batch.jobs.InterestCalculationJob
     * @see com.cardemo.batch.jobs.TransactionReportJob
     * @see com.cardemo.batch.writers.RejectWriter
     * @see com.cardemo.batch.writers.StatementWriter
     * @see com.cardemo.batch.writers.TransactionWriter
     */
    public static final DateTimeFormatter BACKUP_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * COBOL-format timestamp: {@code "yyyy-MM-dd-HH.mm.ss.SSSSSS"}.
     *
     * <p>Matches the COBOL {@code PIC X(26)} convention for {@code TRAN-ORIG-TS}
     * and {@code TRAN-PROC-TS} fields. Hyphens separate date components, dots
     * separate time components, and trailing microseconds are formatted as six
     * digits. Used when reading/writing transaction records from the legacy
     * daily transaction file format and when producing combined transaction
     * output for downstream batch stages.</p>
     *
     * <p>COBOL source traceability: maps the {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS} fields defined in copybook {@code CVTRA05Y.cpy}
     * (transaction record layout). Example rendered value:
     * {@code 2022-07-18-10.30.00.000000} (26 characters total).</p>
     *
     * @see com.cardemo.batch.jobs.CombineTransactionsJob
     * @see com.cardemo.batch.readers.DailyTransactionReader
     */
    public static final DateTimeFormatter COBOL_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * Primary daily-transaction timestamp: {@code "yyyy-MM-dd HH:mm:ss.SSSSSS"}.
     *
     * <p>ASCII variant of the COBOL timestamp used by the daily transaction
     * file produced by an upstream transaction generator. Space separates date
     * and time; colons separate time components; trailing microseconds are
     * formatted as six digits. The reader first attempts this primary format
     * before falling back to {@link #COBOL_TIMESTAMP_FORMATTER}.</p>
     *
     * <p>COBOL source traceability: mirrors the 26-character ASCII timestamp
     * emitted by the upstream transaction generator file consumed by
     * {@code CBTRN01C.cbl} (daily transaction reader). Example rendered value:
     * {@code 2022-06-10 19:27:53.000000} (26 characters total).</p>
     *
     * @see com.cardemo.batch.readers.DailyTransactionReader
     */
    public static final DateTimeFormatter PRIMARY_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * DB2-compatible timestamp: {@code "yyyy-MM-dd-HH.mm.ss.SSS000"}.
     *
     * <p>Matches the COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph output
     * format (millisecond precision padded with three trailing literal zeros
     * to match the DB2 {@code TIMESTAMP(6)} column width). Used by
     * {@code TransactionWriter} when serializing processed transactions for
     * posting to the database-equivalent target in the refactored Spring Boot
     * implementation.</p>
     *
     * <p>COBOL source traceability: maps the COBOL {@code STRING} pattern
     * {@code YYYY-MM-DD-HH.MM.SS.NN0000} produced by the
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph of {@code CBTRN02C.cbl}.
     * The trailing {@code 000} literal pads the three-digit {@code SSS}
     * milliseconds output to six fractional digits, preserving DB2
     * {@code TIMESTAMP(6)} width parity.</p>
     *
     * @see com.cardemo.batch.writers.TransactionWriter
     */
    public static final DateTimeFormatter DB2_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS000");

    /**
     * ISO 8601 date formatter: {@code "yyyy-MM-dd"}.
     *
     * <p>Used by {@link com.cardemo.service.report.ReportSubmissionService} to
     * serialize report start/end dates into the SQS FIFO message payload sent
     * to the transaction report batch job queue.</p>
     *
     * <p>COBOL source traceability: matches the COBOL {@code WS-DATE-FORMAT}
     * convention of {@code CORPT00C.cbl} (line 72) used when dispatching
     * report-submission TDQ entries prior to the SQS-based replacement
     * described in Decision D-004.</p>
     *
     * @see com.cardemo.service.report.ReportSubmissionService
     */
    public static final DateTimeFormatter ISO_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // -------------------------------------------------------------------------
    // Supporting date-validation integer and String constants (originally
    // defined in DateValidationService) — AAP §0.5.1 includes these in
    // DateFormatConstants to complete the centralization of date-handling
    // support values. Values mirror the COBOL CSUTLDWY.cpy working-storage
    // section (lines 9-57) verbatim.
    // -------------------------------------------------------------------------

    /**
     * Months containing 31 days, per the Gregorian calendar.
     *
     * <p>Unmodifiable set used by {@code DateValidationService} day-of-month
     * validation to distinguish 30-day months, 31-day months, and February.
     * {@link Set#of(Object, Object, Object, Object, Object, Object, Object)}
     * returns an unmodifiable (structurally-immutable) {@link Set} factory
     * introduced in Java 9+.</p>
     *
     * <p>COBOL source traceability: mirrors the 88-level condition
     * {@code WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12} from
     * {@code CSUTLDWY.cpy} (lines 21-23).</p>
     */
    public static final Set<Integer> MONTHS_WITH_31_DAYS = Set.of(1, 3, 5, 7, 8, 10, 12);

    /**
     * Numeric month representing February.
     *
     * <p>Used by date validation to branch into February-specific leap-year
     * day-of-month checks (28 days in non-leap years, 29 days in leap years).</p>
     *
     * <p>COBOL source traceability: mirrors {@code WS-FEBRUARY VALUE 2} from
     * {@code CSUTLDWY.cpy} (line 24).</p>
     */
    public static final int FEBRUARY = 2;

    /**
     * Valid century prefix representing the 1900s.
     *
     * <p>Matches the COBOL century validation range where the first two digits
     * of a CCYYMMDD date must be {@code 19} or {@code 20}.</p>
     *
     * <p>COBOL source traceability: mirrors {@code LAST-CENTURY VALUE 19} from
     * {@code CSUTLDWY.cpy} (line 10).</p>
     */
    public static final int LAST_CENTURY = 19;

    /**
     * Valid century prefix representing the 2000s.
     *
     * <p>Matches the COBOL century validation range where the first two digits
     * of a CCYYMMDD date must be {@code 19} or {@code 20}.</p>
     *
     * <p>COBOL source traceability: mirrors {@code THIS-CENTURY VALUE 20} from
     * {@code CSUTLDWY.cpy} (line 9).</p>
     */
    public static final int THIS_CENTURY = 20;

    /**
     * Length of a valid CCYYMMDD date string.
     *
     * <p>Exactly 8 characters: 4-digit century+year, 2-digit month, 2-digit
     * day. Used by {@code DateValidationService} length-precheck before
     * attempting strict parsing via {@link #CCYYMMDD_STRICT_FORMATTER}.</p>
     */
    public static final int CCYYMMDD_LENGTH = 8;

    /**
     * Severity-0 feedback code message indicating CEEDAYS (COBOL date
     * validation service) succeeded.
     *
     * <p>String returned by {@code DateValidationService} when an input date
     * is semantically valid, emulating the COBOL {@code CEE000} severity-0
     * feedback code from {@code CSUTLDTC.cbl}. Callers MAY compare result
     * text against this constant to detect success paths without inspecting
     * the numeric severity code directly.</p>
     */
    public static final String CEEDAYS_VALID = "Date is valid";

    /**
     * Private constructor prevents instantiation of this constant holder class.
     *
     * @throws AssertionError always, to defend against reflection-based
     *                       instantiation attempts.
     */
    private DateFormatConstants() {
        throw new AssertionError(
                "DateFormatConstants is a constant holder and must not be instantiated.");
    }
}
