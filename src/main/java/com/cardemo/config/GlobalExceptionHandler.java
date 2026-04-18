/*
 * GlobalExceptionHandler.java
 *
 * Global exception handler for the CardDemo REST API.
 *
 * This @RestControllerAdvice class was extracted from WebConfig.java to follow
 * the single-responsibility principle (AAP §0.7.2 — WebConfig Decomposition).
 * It contains the complete exception-to-HTTP-status mapping for the CardDemo
 * exception hierarchy and the colocated ErrorResponse record.
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
 * - §0.7.2: WebConfig Decomposition — extracted from WebConfig.java
 * - §0.8.4: Transaction/concurrency error mapping (SYNCPOINT → @Transactional)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.config;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.ConcurrentModificationException;
import com.cardemo.exception.CreditLimitExceededException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.ExpiredCardException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global exception handler for the CardDemo REST API.
 *
 * <p>Maps the CardDemo exception hierarchy to appropriate HTTP status codes
 * with structured {@link ErrorResponse} JSON bodies. This centralized handler
 * replaces the per-program error handling pattern in the original COBOL BMS
 * 3270 architecture.</p>
 *
 * <h3>Exception-to-HTTP Status Mapping (from COBOL Error Patterns)</h3>
 * <table>
 *   <caption>Exception mappings from COBOL FILE STATUS and CICS RESP codes</caption>
 *   <tr><th>Exception</th><th>HTTP Status</th><th>COBOL Origin</th></tr>
 *   <tr><td>{@link RecordNotFoundException}</td><td>404</td>
 *       <td>FILE STATUS 23, DFHRESP(NOTFND)</td></tr>
 *   <tr><td>{@link DuplicateRecordException}</td><td>409</td>
 *       <td>FILE STATUS 22, DFHRESP(DUPKEY/DUPREC)</td></tr>
 *   <tr><td>{@link ConcurrentModificationException}</td><td>409</td>
 *       <td>CICS REWRITE failure, LOCKED-BUT-UPDATE-FAILED</td></tr>
 *   <tr><td>{@link CreditLimitExceededException}</td><td>422</td>
 *       <td>Reject code 102 (OVERLIMIT TRANSACTION)</td></tr>
 *   <tr><td>{@link ExpiredCardException}</td><td>422</td>
 *       <td>Reject code 103 (ACCT EXPIRATION)</td></tr>
 *   <tr><td>{@link ValidationException}</td><td>400</td>
 *       <td>COACTUPC 9700-CHECK-CHANGE-IN-REC</td></tr>
 *   <tr><td>{@link ConstraintViolationException}</td><td>400</td>
 *       <td>CSSETATY.cpy field attribute validation</td></tr>
 *   <tr><td>{@link CardDemoException}</td><td>500</td>
 *       <td>Catch-all for application errors</td></tr>
 *   <tr><td>{@link Exception}</td><td>500</td>
 *       <td>Generic catch-all (no detail exposure)</td></tr>
 * </table>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** MDC key for the request correlation ID injected by the observability filter. */
    private static final String CORRELATION_ID_KEY = "correlationId";

    /**
     * Handles {@link RecordNotFoundException} — COBOL FILE STATUS 23 / DFHRESP(NOTFND).
     *
     * <p>Returns HTTP 404 (Not Found) with structured error details. This maps
     * from the COBOL pattern where INVALID KEY on READ/START/DELETE operations
     * triggers a display message to the 3270 terminal.</p>
     *
     * @param ex      the record-not-found exception containing entity context
     * @param request the HTTP request for URI extraction
     * @return HTTP 404 response with structured error body
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecordNotFoundException(
            RecordNotFoundException ex, HttpServletRequest request) {
        log.warn("Record not found [errorCode={}, fileStatus={}]: {}",
                ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles {@link DuplicateRecordException} — COBOL FILE STATUS 22 / DFHRESP(DUPKEY).
     *
     * <p>Returns HTTP 409 (Conflict) for duplicate key violations during write
     * operations. Maps from COBOL WRITE INVALID KEY patterns in COTRN02C.cbl
     * and COUSR01C.cbl.</p>
     *
     * @param ex      the duplicate-record exception with entity context
     * @param request the HTTP request for URI extraction
     * @return HTTP 409 response with structured error body
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateRecordException(
            DuplicateRecordException ex, HttpServletRequest request) {
        log.warn("Duplicate record [errorCode={}, fileStatus={}]: {}",
                ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT, ex.getMessage(), ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles {@link ConcurrentModificationException} — CICS REWRITE snapshot mismatch.
     *
     * <p>Returns HTTP 409 (Conflict) for optimistic locking failures. Maps from
     * COBOL LOCKED-BUT-UPDATE-FAILED and DATA-WAS-CHANGED-BEFORE-UPDATE flags
     * in COACTUPC.cbl and COCRDUPC.cbl. The Spring {@code @Transactional} rollback
     * preserves COBOL SYNCPOINT ROLLBACK semantics.</p>
     *
     * @param ex      the concurrent-modification exception with entity context
     * @param request the HTTP request for URI extraction
     * @return HTTP 409 response with structured error body
     */
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentModificationException(
            ConcurrentModificationException ex, HttpServletRequest request) {
        log.warn("Concurrent modification [errorCode={}]: {}",
                ex.getErrorCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.CONFLICT, ex.getMessage(), ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handles {@link CreditLimitExceededException} — COBOL reject code 102.
     *
     * <p>Returns HTTP 422 (Unprocessable Entity) when a transaction amount
     * exceeds the account credit limit. Maps from CBTRN02C.cbl paragraph
     * 1500-B-LOOKUP-ACCT where WS-VALIDATION-FAIL-REASON is set to 102
     * with description 'OVERLIMIT TRANSACTION'.</p>
     *
     * @param ex      the credit-limit-exceeded exception with financial context
     * @param request the HTTP request for URI extraction
     * @return HTTP 422 response with structured error body
     */
    @ExceptionHandler(CreditLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleCreditLimitExceededException(
            CreditLimitExceededException ex, HttpServletRequest request) {
        log.warn("Credit limit exceeded [errorCode={}]: {}",
                ex.getErrorCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(),
                ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Handles {@link ExpiredCardException} — COBOL reject code 103.
     *
     * <p>Returns HTTP 422 (Unprocessable Entity) when a transaction is
     * attempted on an expired account. Maps from CBTRN02C.cbl paragraph
     * 1500-B-LOOKUP-ACCT where WS-VALIDATION-FAIL-REASON is set to 103
     * with description 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'.</p>
     *
     * @param ex      the expired-card exception with date context
     * @param request the HTTP request for URI extraction
     * @return HTTP 422 response with structured error body
     */
    @ExceptionHandler(ExpiredCardException.class)
    public ResponseEntity<ErrorResponse> handleExpiredCardException(
            ExpiredCardException ex, HttpServletRequest request) {
        log.warn("Expired card/account [errorCode={}]: {}",
                ex.getErrorCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(),
                ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    /**
     * Handles {@link ValidationException} — COBOL field-level validation failures.
     *
     * <p>Returns HTTP 400 (Bad Request) with per-field error details. Maps from
     * COACTUPC.cbl paragraph 9700-CHECK-CHANGE-IN-REC which validates 15+ fields
     * individually, and from CSLKPCDY.cpy NANPA/state/ZIP validation tables.</p>
     *
     * <p>The {@code fieldErrors} list in the response provides structured
     * per-field validation error details (field name, rejected value, message),
     * enabling API consumers to highlight specific input fields.</p>
     *
     * @param ex      the validation exception with field-level error details
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with structured error body including fieldErrors
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            ValidationException ex, HttpServletRequest request) {
        log.warn("Validation failure [errorCode={}, fields={}]: {}",
                ex.getErrorCode(), ex.getFieldErrors().size(), ex.getMessage());
        List<Map<String, String>> fieldErrorList = ex.getFieldErrors().stream()
                .map(fe -> {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("field", fe.fieldName());
                    entry.put("rejectedValue", fe.rejectedValue());
                    entry.put("message", fe.message());
                    return entry;
                })
                .toList();
        List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getErrorCode(),
                errors, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link ConstraintViolationException} — Jakarta Bean Validation failures.
     *
     * <p>Returns HTTP 400 (Bad Request) when {@code @Valid} annotation triggers
     * constraint violations on request DTOs. Replaces the COBOL CSSETATY.cpy
     * field attribute validation pattern where BMS screen fields were validated
     * per-field before processing.</p>
     *
     * @param ex      the constraint violation exception from Jakarta Bean Validation
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with structured field error details
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException ex, HttpServletRequest request) {
        log.warn("Constraint violation: {}", ex.getMessage());
        List<Map<String, String>> fieldErrorList = ex.getConstraintViolations().stream()
                .map(violation -> {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("field", violation.getPropertyPath().toString());
                    Object invalidValue = violation.getInvalidValue();
                    entry.put("rejectedValue",
                            invalidValue != null ? String.valueOf(invalidValue) : null);
                    entry.put("message", violation.getMessage());
                    return entry;
                })
                .toList();
        List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, "Validation failed", "VALID",
                errors, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link MethodArgumentNotValidException} — Spring MVC {@code @Valid}
     * annotation failures on {@code @RequestBody} DTOs.
     *
     * <p>Returns HTTP 400 (Bad Request) with per-field error details extracted from
     * the {@link org.springframework.validation.BindingResult}. This handler covers
     * the standard Spring MVC validation path for request body deserialization, which
     * is distinct from the {@link ConstraintViolationException} handler (which covers
     * {@code @Validated} on path/query parameters).</p>
     *
     * <p>Without this handler, Spring's default exception resolution would intercept
     * {@code MethodArgumentNotValidException} and return a 400 response without
     * the structured {@link ErrorResponse} format, breaking the API contract defined
     * in {@code docs/api-contracts.md} section 1.6.</p>
     *
     * @param ex      the method argument validation exception from Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with structured fieldErrors array
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        log.warn("Method argument validation failed: {}", ex.getMessage());
        List<Map<String, String>> fieldErrorList = ex.getBindingResult()
                .getFieldErrors().stream()
                .map(fieldError -> {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("field", fieldError.getField());
                    Object rejectedValue = fieldError.getRejectedValue();
                    entry.put("rejectedValue",
                            rejectedValue != null ? String.valueOf(rejectedValue) : null);
                    entry.put("message", fieldError.getDefaultMessage());
                    return entry;
                })
                .toList();
        List<Map<String, String>> errors = fieldErrorList.isEmpty() ? null : fieldErrorList;
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, "Validation failed", "VALID",
                errors, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link HttpMessageNotReadableException} — malformed or missing
     * request body.
     *
     * <p>Returns HTTP 400 (Bad Request) when the request body cannot be
     * deserialized by Jackson. Common causes include malformed JSON syntax,
     * empty request bodies for endpoints expecting {@code @RequestBody}, and
     * type coercion failures (e.g., string where number expected).</p>
     *
     * <p>This handler is critical for all REST endpoints — without it,
     * malformed payloads fall through to the generic {@code Exception} handler
     * and return HTTP 500, which incorrectly signals a server error for what
     * is a client input problem.</p>
     *
     * <p>No direct COBOL equivalent — BMS 3270 screens enforced field-level
     * formatting at the terminal level before data reached the COBOL program.
     * In the REST API, clients can send arbitrary payloads, so server-side
     * deserialization error handling is essential.</p>
     *
     * @param ex      the message-not-readable exception from Jackson/Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with structured error body
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed request body: {}", ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Malformed or missing request body. Ensure the request contains valid JSON.",
                "BAD_REQUEST", null, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link HttpMediaTypeNotSupportedException} — unsupported
     * Content-Type header.
     *
     * <p>Returns HTTP 415 (Unsupported Media Type) when the request
     * Content-Type header does not match any media type accepted by the
     * target endpoint. For CardDemo REST endpoints, the accepted type is
     * {@code application/json}.</p>
     *
     * <p>Without this handler, unsupported content types fall through to the
     * generic {@code Exception} handler and return HTTP 500, which incorrectly
     * signals a server error for what is a client configuration problem.</p>
     *
     * <p>No direct COBOL equivalent — BMS 3270 terminal protocol had a fixed
     * data encoding (EBCDIC), so content-type negotiation was not applicable.</p>
     *
     * @param ex      the media-type-not-supported exception from Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 415 response with structured error body
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        log.warn("Unsupported media type: {}", ex.getContentType());
        String message = String.format(
                "Content type '%s' is not supported. Use 'application/json'.",
                ex.getContentType());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, message,
                "UNSUPPORTED_MEDIA_TYPE", null, request);
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
    }

    /**
     * Handles {@link HttpRequestMethodNotSupportedException} — returned by
     * Spring MVC when a request uses an HTTP method not supported by the handler.
     *
     * <p>Returns HTTP 405 (Method Not Allowed) instead of Spring's default 500
     * error. This ensures that e.g. GET /api/auth/signin (a POST-only endpoint)
     * returns a proper 405 response with a structured error body.</p>
     *
     * @param ex      the method-not-supported exception from Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 405 response with allowed methods listed in the error message
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("Method not allowed: {} {} (supported: {})",
                request.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods());
        String message = String.format(
                "Request method '%s' is not supported for this endpoint. Supported methods: %s",
                ex.getMethod(), ex.getSupportedHttpMethods());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED, message,
                "METHOD_NOT_ALLOWED", null, request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    /**
     * Handles {@link IllegalArgumentException} — returned when input validation
     * in service methods rejects invalid parameters.
     *
     * <p>Returns HTTP 400 (Bad Request) with the exception message as the error
     * detail. This handler catches validation exceptions thrown by service-layer
     * input checks (e.g., non-numeric card number in
     * {@code CardDetailService.validateCardNumber()}) that are not already covered
     * by Bean Validation or Spring MVC binding exceptions.</p>
     *
     * <p>In the COBOL source, these map to field-level edit paragraphs such as
     * {@code 2220-EDIT-CARD} in COCRDSLC.cbl, which set an error message and
     * re-display the BMS screen. In the REST API target, this maps to HTTP 400
     * with a descriptive error message.</p>
     *
     * @param ex      the IllegalArgumentException from service-layer validation
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with validation error details
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid argument: {} {} — {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, ex.getMessage(),
                "INVALID_ARGUMENT", null, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link CardDemoException} — catch-all for application exceptions.
     *
     * <p>Returns HTTP 500 (Internal Server Error) for any CardDemoException
     * subclass not explicitly handled by the more specific exception handlers
     * above. Logs the full stack trace and the COBOL FILE STATUS code (if
     * present) for debugging and traceability.</p>
     *
     * @param ex      the base CardDemo exception
     * @param request the HTTP request for URI extraction
     * @return HTTP 500 response with error code from the exception hierarchy
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ErrorResponse> handleCardDemoException(
            CardDemoException ex, HttpServletRequest request) {
        log.error("CardDemo error [errorCode={}, fileStatus={}]: {}",
                ex.getErrorCode(), ex.getFileStatusCode(), ex.getMessage(), ex);
        ErrorResponse response = buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(),
                ex.getErrorCode(), null, request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles {@link NoResourceFoundException} — returned by Spring MVC when
     * no handler mapping is found for a request URI.
     *
     * <p>Returns HTTP 404 (Not Found) with a descriptive message indicating
     * the requested resource does not exist. This handler is necessary because
     * {@code NoResourceFoundException} (a {@link org.springframework.web.servlet.resource.NoResourceFoundException})
     * would otherwise be caught by the generic {@code Exception.class} handler
     * and incorrectly returned as HTTP 500.</p>
     *
     * <p>This has no direct COBOL equivalent — in the BMS 3270 architecture,
     * invalid transaction IDs were handled by the CICS transaction routing
     * mechanism, which displayed a "Transaction not found" message on the
     * terminal. In the REST API target, this maps to standard HTTP 404
     * semantics for non-existent endpoints.</p>
     *
     * @param ex      the NoResourceFoundException from Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 404 response with descriptive error message
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {} {}", request.getMethod(), request.getRequestURI());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.NOT_FOUND, ex.getMessage(),
                "RESOURCE_NOT_FOUND", null, request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * Handles {@link MethodArgumentTypeMismatchException} — thrown when a
     * request parameter cannot be converted to the expected type (e.g.,
     * {@code page=abc} where an Integer is expected).
     *
     * <p>Returns HTTP 400 (Bad Request) with a descriptive message identifying
     * the invalid parameter name and expected type. This prevents non-numeric
     * or overflow pagination parameters from falling through to the generic
     * 500 handler.</p>
     *
     * <p>No direct COBOL equivalent — BMS 3270 screens used fixed numeric
     * fields that only accepted digits at the terminal level.</p>
     *
     * @param ex      the type mismatch exception from Spring MVC
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with parameter validation error details
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName() : "unknown";
        String message = String.format(
                "Invalid value for parameter '%s': expected type %s",
                paramName, requiredType);
        log.warn("Method argument type mismatch: {} — {}", paramName, ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, message,
                "INVALID_PARAMETER_TYPE", null, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles {@link InvalidDataAccessApiUsageException} — thrown when JPA
     * receives invalid data access parameters (e.g., a page offset that
     * exceeds {@code Integer.MAX_VALUE}).
     *
     * <p>Returns HTTP 400 (Bad Request) because the root cause is invalid
     * client input (excessively large page numbers) rather than a server
     * error. This prevents extreme pagination values from generating 500
     * responses.</p>
     *
     * <p>COBOL traceability: CICS STARTBR with an invalid key would return
     * FILE STATUS '23' (record not found); the Java equivalent maps this
     * to a client-side validation error rather than a server error.</p>
     *
     * @param ex      the invalid data access API usage exception
     * @param request the HTTP request for URI extraction
     * @return HTTP 400 response with descriptive error message
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccessApiUsage(
            InvalidDataAccessApiUsageException ex, HttpServletRequest request) {
        String message = "Invalid query parameter value: " + ex.getMessage();
        log.warn("Invalid data access API usage: {}", ex.getMessage());
        ErrorResponse response = buildErrorResponse(
                HttpStatus.BAD_REQUEST, message,
                "INVALID_DATA_ACCESS", null, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles generic {@link Exception} — ultimate catch-all for unexpected errors.
     *
     * <p>Returns HTTP 500 (Internal Server Error) with a generic message that
     * does not expose internal implementation details (security best practice).
     * Logs the full stack trace for debugging.</p>
     *
     * <p>This handler ensures that no unhandled exception results in a raw
     * stack trace being returned to API consumers.</p>
     *
     * @param ex      the unexpected exception
     * @param request the HTTP request for URI extraction
     * @return HTTP 500 response with generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ErrorResponse response = buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support.",
                null, null, request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Builds a structured {@link ErrorResponse} with observability context.
     *
     * <p>Populates the response with the current UTC timestamp and the
     * correlation ID from MDC (injected by the observability filter per
     * AAP §0.7.1). The request URI is extracted from the servlet request
     * for traceability.</p>
     *
     * @param status      the HTTP status for the error response
     * @param message     the human-readable error message
     * @param errorCode   the application error code (may be {@code null})
     * @param fieldErrors optional list of per-field validation errors
     * @param request     the HTTP servlet request for URI extraction
     * @return the fully populated error response
     */
    private ErrorResponse buildErrorResponse(HttpStatus status, String message,
            String errorCode, List<Map<String, String>> fieldErrors,
            HttpServletRequest request) {
        return new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                errorCode,
                fieldErrors,
                Instant.now().toString(),
                request.getRequestURI(),
                MDC.get(CORRELATION_ID_KEY)
        );
    }

    /**
     * Structured error response DTO for all REST API error responses.
     *
     * <p>This record provides a consistent JSON error format across all exception
     * types handled by the enclosing {@link GlobalExceptionHandler}. It includes
     * observability fields ({@code correlationId}, {@code timestamp}) and COBOL
     * traceability fields ({@code errorCode}) for comprehensive error reporting.</p>
     *
     * <p>Visibility &amp; Placement: This record is declared as a
     * {@code public} nested record inside {@link GlobalExceptionHandler} to
     * preserve byte-for-byte structural parity with the source
     * {@code WebConfig.ErrorResponse} (source {@code WebConfig.java} line 274,
     * which declared {@code public record ErrorResponse} as a nested record
     * of {@code WebConfig}). The fully qualified name is therefore
     * {@code com.cardemo.config.GlobalExceptionHandler.ErrorResponse}, as
     * explicitly required by AAP &sect;0.5.2 (Import Transformation Rules)
     * and AAP &sect;0.7.2 (WebConfig Decomposition &mdash; "inner record").
     * Java only permits a single {@code public} top-level type per {@code .java}
     * file; nesting within the enclosing handler class is the correct way to
     * make this type {@code public} while preserving AAP compliance.</p>
     *
     * <p>Example JSON output:</p>
     * <pre>{@code
     * {
     *   "status": 404,
     *   "error": "Not Found",
     *   "message": "Account not found with id: 00000000001",
     *   "errorCode": "RNF",
     *   "timestamp": "2026-03-17T10:15:30.123Z",
     *   "path": "/api/accounts/00000000001",
     *   "correlationId": "abc-123-def"
     * }
     * }</pre>
     *
     * @param status        the HTTP status code (e.g., 404, 409, 422, 500)
     * @param error         the HTTP status reason phrase (e.g., "Not Found")
     * @param message       the human-readable error message
     * @param errorCode     the application error code from the CardDemo exception
     *                      hierarchy (e.g., "RNF", "DUP", "LOCK", "CREDIT",
     *                      "EXPIRY", "VALID", "CARDDEMO_ERROR")
     * @param fieldErrors   optional list of per-field validation errors, each
     *                      containing "field", "rejectedValue", and "message" keys;
     *                      {@code null} when not a validation error
     * @param timestamp     the ISO-8601 UTC timestamp when the error occurred
     * @param path          the request URI that triggered the error
     * @param correlationId the correlation ID from MDC for observability tracing
     */
    public record ErrorResponse(
            int status,
            String error,
            String message,
            String errorCode,
            List<Map<String, String>> fieldErrors,
            String timestamp,
            String path,
            String correlationId
    ) { }
}
