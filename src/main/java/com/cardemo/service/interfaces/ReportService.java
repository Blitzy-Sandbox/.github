/*
 * ReportService.java — Report Submission Service Interface
 *
 * Public service contract for online report-submission workflows in the
 * CardDemo application. Wraps the single public method of the concrete
 * {@code @Service} class
 * {@code com.cardemo.service.report.ReportSubmissionService} which
 * migrates COBOL program CORPT00C.cbl (CICS transaction code CR00).
 *
 * This file implements AAP Section 0.5.1 "New Service Interface Files
 * (CREATE)" and follows the design rules in the folder-level AAP for
 * {@code service/interfaces/}. No business logic is expressed here —
 * this is a pure Java contract (no default methods, no annotations on
 * the type). Exceptions thrown by implementations are Java unchecked
 * exceptions and therefore do NOT appear in {@code throws} clauses,
 * only in Javadoc {@code @throws} tags for documentation.
 *
 * AAP Rule Compliance:
 *   R-001 — No business logic; interface-only declarations
 *   R-004 — COBOL {@see} reference preserved for traceability
 *   R-006 — Method signature matches concrete source exactly
 *           ({@code String submitReport(ReportRequest request)}
 *            at ReportSubmissionService.java line 261)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.interfaces;

import com.cardemo.model.dto.ReportRequest;

/**
 * Service contract for online report submission operations in the CardDemo application.
 *
 * <p>This interface defines the public service boundary for the report domain.
 * The implementation is provided by the Spring-managed {@code @Service} class
 * {@code com.cardemo.service.report.ReportSubmissionService}, which migrates the
 * legacy COBOL program {@code CORPT00C.cbl} (CICS transaction code {@code CR00}).
 * Consumers (e.g., {@code ReportController}) depend on this interface rather
 * than on the concrete class so that alternative implementations may be
 * supplied for testing (mocks) or future extension without modifying the
 * consumer code.</p>
 *
 * <p>The interface represents the online-to-batch bridge in the CardDemo
 * architecture: an online user submits a report request through the REST
 * endpoint, and the implementation validates the request, computes the
 * reporting date range, serializes the request to a JSON payload, and
 * publishes the payload to the SQS FIFO queue
 * {@code carddemo-report-requests.fifo} where it is consumed asynchronously
 * by the {@code BatchPipelineOrchestrator} for downstream batch processing.
 * The orchestration follows these steps:</p>
 * <ol>
 *   <li>Determine the report type (monthly, yearly, or custom) from the
 *       {@link ReportRequest} selectors (maps CORPT00C.cbl
 *       {@code PROCESS-ENTER-KEY EVALUATE TRUE} at lines 212-443).</li>
 *   <li>Calculate the start and end dates for monthly and yearly reports
 *       (defaulting to the current month/year via {@code FUNCTION
 *       CURRENT-DATE}) or validate the user-supplied dates for custom
 *       reports (maps lines 256-436).</li>
 *   <li>Validate the Y/N confirmation indicator before committing to the
 *       submission (maps {@code SUBMIT-JOB-TO-INTRDR} lines 464-494).</li>
 *   <li>Publish the report parameters as a JSON message to the SQS FIFO
 *       queue {@code carddemo-report-requests.fifo}, replacing the original
 *       CICS Transient Data Queue (TDQ) write (maps
 *       {@code WIRTE-JOBSUB-TDQ} lines 515-535).</li>
 *   <li>Return a human-readable confirmation message summarizing the
 *       submission so the controller can render it to the end user
 *       (maps lines 445-456).</li>
 * </ol>
 *
 * <h3>Architectural Decision D-004: SQS for TDQ (Implementation Concern)</h3>
 * <p>Per architectural decision D-004 recorded in {@code DECISION_LOG.md},
 * the original COBOL CICS Transient Data Queue (TDQ) write
 * ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}) that iterated up to 1000
 * 80-byte JCL card images is replaced with a single structured JSON
 * message published to an Amazon SQS FIFO queue. FIFO semantics
 * (message-group ordering and exactly-once processing via a deduplication
 * identifier) preserve the COBOL submission ordering contract. Message
 * construction, deduplication-ID generation, and queue resolution are
 * implementation concerns of {@code ReportSubmissionService} and are
 * intentionally not visible through this interface.</p>
 *
 * <h3>Online-to-Batch Bridge (Implementation Concern)</h3>
 * <p>This interface is the sole online entry point that bridges the online
 * transaction layer to the Spring Batch pipeline. The implementation does
 * not write to the relational database; it exclusively validates input
 * and publishes to SQS. The downstream {@code BatchPipelineOrchestrator}
 * consumes these messages asynchronously and drives the corresponding
 * batch jobs (daily transaction posting, interest calculation, statement
 * generation, and transaction reporting). Because the online path does
 * not mutate database state, the implementation does not carry a
 * class-level {@code @Transactional} annotation.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <p>This reference provides traceability back to the original mainframe
 * program (see {@code TRACEABILITY_MATRIX.md} for the full paragraph-level
 * mapping).</p>
 *
 * @see com.cardemo.model.dto.ReportRequest
 * @see <a href="file://app/cbl/CORPT00C.cbl">CORPT00C.cbl</a> — Online Report
 *      Submission transaction (CICS transaction code {@code CR00})
 */
public interface ReportService {

    /**
     * Processes a report submission request. Determines report type
     * (monthly/yearly/custom), calculates date ranges, validates dates and
     * confirmation, and publishes to SQS for batch processing.
     *
     * <p>Execution flow (matching COBOL CORPT00C.cbl exactly):</p>
     * <ol>
     *   <li>Determine the report type and calculate the date range. For
     *       monthly reports, default to the first and last day of the
     *       current calendar month; for yearly reports, default to
     *       January 1 and December 31 of the current calendar year; for
     *       custom reports, apply the field-by-field date validation
     *       cascade against the user-supplied {@code startDate} and
     *       {@code endDate} (maps {@code PROCESS-ENTER-KEY} at lines
     *       208-456 and the {@code CSUTLDTC} date-validation subprogram).</li>
     *   <li>Validate the single-character Y/N confirmation indicator to
     *       guard against accidental submissions (maps
     *       {@code SUBMIT-JOB-TO-INTRDR} at lines 464-494).</li>
     *   <li>Serialize the report parameters (report name, start date, end
     *       date) to a JSON payload and publish the payload to the SQS
     *       FIFO queue {@code carddemo-report-requests.fifo} using a
     *       message-group identifier of {@code report-submissions} and a
     *       UUID-based deduplication identifier (maps the original
     *       {@code WIRTE-JOBSUB-TDQ} TDQ write at lines 515-535).</li>
     *   <li>Return a human-readable confirmation message (maps lines
     *       445-456: {@code STRING WS-REPORT-NAME DELIMITED BY SPACE
     *       ' report submitted for printing ...' DELIMITED BY SIZE}).</li>
     * </ol>
     *
     * <p>Key COBOL paragraph mappings:</p>
     * <ul>
     *   <li>{@code PROCESS-ENTER-KEY} (CORPT00C.cbl lines 208-456) — report
     *       type selection, date range computation, and custom-date
     *       validation cascade</li>
     *   <li>{@code SUBMIT-JOB-TO-INTRDR} (lines 462-510) — confirmation
     *       validation and submission orchestration</li>
     *   <li>{@code WIRTE-JOBSUB-TDQ} (lines 515-535) — TDQ write now
     *       replaced with SQS FIFO publish per architectural decision
     *       D-004</li>
     * </ul>
     *
     * <p>The implementation does not write to the relational database and
     * therefore does not carry a {@code @Transactional} annotation;
     * validation errors are reported via unchecked exceptions before any
     * external side effect, and the SQS publish is performed as a single
     * atomic AWS SDK call.</p>
     *
     * @param request the {@link ReportRequest} DTO containing the report
     *                type selection ({@code monthly}, {@code yearly}, or
     *                {@code custom}), optional custom {@code startDate}
     *                and {@code endDate}, and the single-character
     *                {@code confirm} indicator (maps COBOL BMS symbolic
     *                map {@code CORPT0AI} / copybook {@code CORPT00.CPY});
     *                must not be null and must select exactly one report
     *                type
     * @return a confirmation message string summarizing the accepted
     *         submission (e.g., {@code "Monthly report submitted for
     *         printing ..."}) that the controller renders to the end user
     * @throws com.cardemo.exception.ValidationException if the request is
     *         null, no report type is selected (maps the {@code WHEN
     *         OTHER} branch at CORPT00C.cbl lines 437-443 "Select a report
     *         type to print report..."), the confirmation indicator is
     *         missing or not {@code Y}/{@code N}, or — for custom reports
     *         — any date field is missing, malformed, or inconsistent
     *         (e.g., {@code startDate} after {@code endDate})
     * @throws com.cardemo.exception.CardDemoException if JSON serialization
     *         of the report payload fails or the SQS publish call fails
     *         (wrapping the underlying AWS SDK infrastructure exception);
     *         maps the COBOL TDQ write failure path
     * @see <a href="file://app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
     *      {@code PROCESS-ENTER-KEY} and {@code SUBMIT-JOB-TO-INTRDR}
     *      paragraphs (and {@code WIRTE-JOBSUB-TDQ}, now replaced by SQS)
     */
    String submitReport(ReportRequest request);
}
