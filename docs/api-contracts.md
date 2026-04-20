# CardDemo REST API Contracts

**Document Version**: 1.0
**Status**: Authoritative
**Scope**: Specifies every HTTP endpoint exposed by the CardDemo Spring Boot application, including request/response schemas, authentication and authorization requirements, HTTP status codes, and mappings to the original COBOL/CICS programs.
**Audience**: Backend engineers, QA engineers, operators, integration partners.

This document is the single source of truth for the CardDemo REST contract. It is referenced directly from source code (e.g., `MenuController.java`, `GlobalExceptionHandler.java`), operational tooling (`docs/validation-gates.md` Gate 5), and onboarding materials (`README.md`, `docs/onboarding-guide.md`). Any divergence between this document and the runtime behavior constitutes a defect.

---

## Table of Contents

- [1. Overview and Conventions](#1-overview-and-conventions)
  - [1.1 API Surface Summary](#11-api-surface-summary)
  - [1.2 Base URL and Media Types](#12-base-url-and-media-types)
  - [1.3 Authentication and Authorization](#13-authentication-and-authorization)
  - [1.4 Standard HTTP Status Codes](#14-standard-http-status-codes)
  - [1.5 Error Codes](#15-error-codes)
  - [1.6 ErrorResponse Structure](#16-errorresponse-structure)
  - [1.7 Correlation ID and Tracing](#17-correlation-id-and-tracing)
- [2. Authentication — `/api/auth`](#2-authentication--apiauth)
  - [2.1 Sign In](#21-sign-in--post-apiauthsignin)
- [3. Account Management — `/api/accounts`](#3-account-management--apiaccounts)
  - [3.1 View Account](#31-view-account--get-apiaccountsid)
  - [3.2 Update Account](#32-update-account--put-apiaccountsid)
- [4. Card Management — `/api/cards`](#4-card-management--apicards)
  - [4.1 List Cards](#41-list-cards--get-apicards)
  - [4.2 List Cards by Account](#42-list-cards-by-account--get-apicardsaccountacctid)
  - [4.3 View Card](#43-view-card--get-apicardscardnum)
  - [4.4 Update Card](#44-update-card--put-apicardscardnum)
- [5. Transaction Management — `/api/transactions`](#5-transaction-management--apitransactions)
  - [5.1 List Transactions](#51-list-transactions--get-apitransactions)
  - [5.2 View Transaction](#52-view-transaction--get-apitransactionsid)
  - [5.3 Add Transaction](#53-add-transaction--post-apitransactions)
  - [5.4 Copy Transaction](#54-copy-transaction--get-apitransactionscopysourceid)
- [6. Billing — `/api/billing`](#6-billing--apibilling)
  - [6.1 Pay Bill](#61-pay-bill--post-apibillingpay)
- [7. Reports — `/api/reports`](#7-reports--apireports)
  - [7.1 Submit Report](#71-submit-report--post-apireportssubmit)
- [8. User Administration — `/api/admin/users`](#8-user-administration--apiadminusers)
  - [8.1 List Users](#81-list-users--get-apiadminusers)
  - [8.2 View User](#82-view-user--get-apiadminusersid)
  - [8.3 Add User](#83-add-user--post-apiadminusers)
  - [8.4 Update User](#84-update-user--put-apiadminusersid)
  - [8.5 Delete User](#85-delete-user--delete-apiadminusersid)
- [9. Menu Navigation — `/api/menu`](#9-menu-navigation--apimenu)
  - [9.1 Main Menu](#91-main-menu--get-apimenumain)
  - [9.2 Admin Menu](#92-admin-menu--get-apimenuadmin)
- [Appendix A — Endpoint Index (Alphabetical)](#appendix-a--endpoint-index-alphabetical)
- [Appendix B — COBOL to REST Traceability](#appendix-b--cobol-to-rest-traceability)
- [Appendix C — DTO Field Reference](#appendix-c--dto-field-reference)

---

## 1. Overview and Conventions

### 1.1 API Surface Summary

CardDemo exposes **19 REST endpoints** across **8 controllers** plus the Spring Boot Actuator endpoints (documented separately in `docs/onboarding-guide.md` §7). Every endpoint is a synchronous request/response JSON API; there are no streaming, WebSocket, or server-sent event endpoints.

| # | Method | Path                                    | Controller             | Auth   | Brief                                  |
|---|--------|-----------------------------------------|------------------------|--------|----------------------------------------|
| 1 | POST   | `/api/auth/signin`                      | AuthController         | public | Authenticate and obtain routing metadata |
| 2 | GET    | `/api/accounts/{id}`                    | AccountController      | USER   | View a single account with customer detail |
| 3 | PUT    | `/api/accounts/{id}`                    | AccountController      | USER   | Update account + customer atomically |
| 4 | GET    | `/api/cards`                            | CardController         | USER   | Paginated list of cards (filterable) |
| 5 | GET    | `/api/cards/account/{acctId}`           | CardController         | USER   | All cards belonging to an account |
| 6 | GET    | `/api/cards/{cardNum}`                  | CardController         | USER   | View a single card |
| 7 | PUT    | `/api/cards/{cardNum}`                  | CardController         | USER   | Update a card |
| 8 | GET    | `/api/transactions`                     | TransactionController  | USER   | Paginated list of transactions |
| 9 | GET    | `/api/transactions/{id}`                | TransactionController  | USER   | View a single transaction |
| 10| POST   | `/api/transactions`                     | TransactionController  | USER   | Create a transaction (auto-generated ID) |
| 11| GET    | `/api/transactions/copy/{sourceId}`     | TransactionController  | USER   | Fetch a template populated from an existing transaction |
| 12| POST   | `/api/billing/pay`                      | BillingController      | USER   | Pay the full outstanding balance on an account |
| 13| POST   | `/api/reports/submit`                   | ReportController       | USER   | Queue a report for asynchronous batch generation |
| 14| GET    | `/api/admin/users`                      | UserAdminController    | ADMIN  | Paginated list of users |
| 15| GET    | `/api/admin/users/{id}`                 | UserAdminController    | ADMIN  | View a single user |
| 16| POST   | `/api/admin/users`                      | UserAdminController    | ADMIN  | Create a user |
| 17| PUT    | `/api/admin/users/{id}`                 | UserAdminController    | ADMIN  | Update a user |
| 18| DELETE | `/api/admin/users/{id}`                 | UserAdminController    | ADMIN  | Delete a user |
| 19| GET    | `/api/menu/{type}`                      | MenuController         | USER   | Retrieve menu options (`main` or `admin`) |

Section 1.1's endpoint count of **19** is authoritative. Documentation referring to a different count (e.g., earlier "22" or "18" figures) reflects historical drift rather than the live contract.

### 1.2 Base URL and Media Types

- **Base URL (local dev)**: `http://localhost:8080`
- **Base URL (docker compose)**: `http://localhost:8080`
- **Request Content-Type**: `application/json` for every endpoint that accepts a body (POST/PUT). The `Content-Type` header MUST be supplied; a missing or mismatched value produces HTTP 415 Unsupported Media Type.
- **Response Content-Type**: `application/json` for every successful response and for every error response handled by `GlobalExceptionHandler`. A handful of error paths (e.g., Spring Security 401 challenges) return `text/plain` or an empty body.
- **Character Encoding**: UTF-8 throughout. COBOL EBCDIC-to-Unicode translation is performed upstream during ETL; the REST API operates exclusively on UTF-8 Unicode.
- **Date formats**: ISO-8601. `LocalDate` fields serialize as `yyyy-MM-dd`. `LocalDateTime` fields serialize as `yyyy-MM-dd'T'HH:mm:ss` (no zone; stored and returned in the application's configured zone).
- **Monetary values**: All amounts are JSON numbers that decode to `java.math.BigDecimal` with scale 2. Clients MUST NOT use floating-point representations when parsing these values (per Decision D-001 in `DECISION_LOG.md`).

### 1.3 Authentication and Authorization

- **Authentication mechanism**: HTTP Basic Auth (per Decision D-010). Clients include an `Authorization: Basic <base64(user:password)>` header on every authenticated request. The `/api/auth/signin` endpoint also accepts credentials inside the request body for COBOL-parity sign-on flow; the sign-on response includes routing metadata (COBOL `CDEMO-TO-TRANID`/`CDEMO-TO-PROGRAM` equivalents) rather than a bearer token.
- **Password storage**: BCrypt (per Decision D-002). Passwords are compared only via the configured `PasswordEncoder`; plaintext passwords never leave the controller boundary.
- **Authorization roles**:
  - `ROLE_USER` — standard user, maps to COBOL `SEC-USR-TYPE = 'U'`.
  - `ROLE_ADMIN` — administrator, maps to COBOL `SEC-USR-TYPE = 'A'`.
- **Endpoint access matrix**:
  - `permitAll` (no auth): `POST /api/auth/signin`, `GET /actuator/health/**`, `GET /actuator/info`, `GET /actuator/prometheus`.
  - `ROLE_ADMIN` only: `/api/admin/**`, `/actuator/**` (except the three public Actuator paths above).
  - `authenticated` (USER or ADMIN): all other `/api/**` endpoints.
- **Sessions**: The API is stateless per Decision D-005. No `JSESSIONID` or server-side session is maintained. Each request is authenticated independently.

### 1.4 Standard HTTP Status Codes

| Code | Meaning                     | When returned                                                                 |
|------|-----------------------------|-------------------------------------------------------------------------------|
| 200  | OK                          | Successful read or update; successful cancellation on POST endpoints          |
| 201  | Created                     | Successful creation (POST `/api/transactions`, POST `/api/billing/pay` on confirm=Y, POST `/api/admin/users`) |
| 202  | Accepted                    | Report submission successfully queued to SQS (POST `/api/reports/submit`)     |
| 204  | No Content                  | Successful delete (DELETE `/api/admin/users/{id}`)                            |
| 400  | Bad Request                 | `ValidationException`, `ConstraintViolationException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `IllegalArgumentException`, `MethodArgumentTypeMismatchException` |
| 401  | Unauthorized                | Missing/invalid credentials; unified auth failure (prevents user enumeration) |
| 403  | Forbidden                   | `AccessDeniedException` — authenticated user lacks required role              |
| 404  | Not Found                   | `RecordNotFoundException`, `NoResourceFoundException` — target resource absent |
| 405  | Method Not Allowed          | `HttpRequestMethodNotSupportedException`                                      |
| 409  | Conflict                    | `DuplicateRecordException` (COBOL FILE STATUS 22), `ConcurrentModificationException` (COBOL DATA-WAS-CHANGED-BEFORE-UPDATE) |
| 415  | Unsupported Media Type      | `HttpMediaTypeNotSupportedException`, `MultipartException`                    |
| 422  | Unprocessable Entity        | `CreditLimitExceededException` (COBOL reject 102), `ExpiredCardException`     |
| 500  | Internal Server Error       | `CardDemoException` (catch-all for unexpected application failures)           |

### 1.5 Error Codes

Error responses include a machine-readable `errorCode` field (in addition to the HTTP status) that maps directly to the thrown exception type. Use this code — not the status or human-readable message — for programmatic error handling.

| `errorCode`       | Java Exception                       | HTTP Status | COBOL Origin / FILE STATUS                        |
|-------------------|--------------------------------------|-------------|---------------------------------------------------|
| `RNF`             | `RecordNotFoundException`            | 404         | FILE STATUS 23 / `DFHRESP(NOTFND)`                |
| `DUP`             | `DuplicateRecordException`           | 409         | FILE STATUS 22 / `DFHRESP(DUPREC)`                |
| `LOCK`            | `ConcurrentModificationException`    | 409         | COBOL `DATA-WAS-CHANGED-BEFORE-UPDATE` (9700-CHECK-CHANGE-IN-REC) |
| `CREDIT`          | `CreditLimitExceededException`       | 422         | COBOL reject code 102 (posting validation)        |
| `EXPIRY`          | `ExpiredCardException`               | 422         | COBOL expiry check (pre-posting)                  |
| `VALID`           | `ValidationException`, bean-validation errors | 400  | COBOL `1200-EDIT-MAP-INPUTS` rejections           |
| `CARDDEMO_ERROR`  | `CardDemoException` (default)        | 500         | Generic application fault                         |

### 1.6 ErrorResponse Structure

Every error handled by `com.cardemo.config.GlobalExceptionHandler` produces a JSON body matching the following record structure:

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Account not found with id: 00000000001",
  "errorCode": "RNF",
  "fieldErrors": null,
  "timestamp": "2026-03-17T10:15:30.123Z",
  "path": "/api/accounts/00000000001",
  "correlationId": "abc-123-def"
}
```

**Schema:**

| Field           | Type                          | Nullable | Description                                                                             |
|-----------------|-------------------------------|----------|-----------------------------------------------------------------------------------------|
| `status`        | integer                       | no       | Numeric HTTP status (duplicates the HTTP response line for convenience)                 |
| `error`         | string                        | no       | HTTP reason phrase (e.g., `Not Found`, `Bad Request`)                                   |
| `message`       | string                        | no       | Human-readable description of the failure; safe to surface to trusted clients            |
| `errorCode`     | string                        | no       | Machine-readable code per §1.5                                                          |
| `fieldErrors`   | array of `{field, message}` objects, or `null` | yes | Populated only for validation errors; each entry names the rejecting field and its message |
| `timestamp`     | string (ISO-8601 UTC)         | no       | Server-side timestamp of the failure                                                    |
| `path`          | string                        | no       | Request path that produced the error (echoed for client convenience)                    |
| `correlationId` | string                        | yes      | Populated from the `X-Correlation-Id` request header; auto-generated when absent        |

**Field-error example** (400 Bad Request from `PUT /api/accounts/{id}` with invalid input):

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errorCode": "VALID",
  "fieldErrors": [
    { "field": "custSsn", "message": "SSN must be 9 digits" },
    { "field": "custFicoScore", "message": "FICO score must be between 300 and 850" }
  ],
  "timestamp": "2026-03-17T10:15:30.123Z",
  "path": "/api/accounts/00000000010",
  "correlationId": "abc-123-def"
}
```

### 1.7 Correlation ID and Tracing

- Clients MAY supply an `X-Correlation-Id` header on any request; the server echoes this value in the response `ErrorResponse.correlationId` field, in structured logs, and in Micrometer/OpenTelemetry spans (per Decision D-018).
- When the header is absent, `com.cardemo.observability.CorrelationIdFilter` generates a UUID and propagates it through the same channels.
- Include the correlation ID in bug reports; it allows support to locate the corresponding log records and traces across the application and its dependencies.

---

## 2. Authentication — `/api/auth`

COBOL Origin: **COSGN00C.cbl** (sign-on program), CICS transaction `CC00`, BMS map `COSGN00`.

### 2.1 Sign In — `POST /api/auth/signin`

Authenticates the supplied credentials against the `USRSEC` table (replacing the COBOL `USRSEC` VSAM file) and returns routing metadata that the client uses to drive the next screen.

- **Controller**: `com.cardemo.controller.AuthController#signIn`
- **COBOL paragraphs**: `PROCESS-ENTER-KEY` → `READ-USER-SEC-FILE` → `SEND-SIGNON-SCREEN`
- **Auth**: public (`permitAll`)
- **Idempotent**: No (authentication is a side-effect-bearing operation in audit logs)

**Request**

| Header            | Required | Notes                                      |
|-------------------|----------|--------------------------------------------|
| `Content-Type`    | yes      | `application/json`                         |
| `X-Correlation-Id`| no       | Propagates into audit logs                 |

Body (`SignOnRequest`):

```json
{
  "userId": "ADMIN001",
  "password": "Password1!"
}
```

| Field      | Type   | Required | Constraints                         | COBOL Source |
|------------|--------|----------|-------------------------------------|--------------|
| `userId`   | string | yes      | `@NotBlank`, `@Size(max=8)`         | `SEC-USR-ID PIC X(08)` |
| `password` | string | yes      | `@NotBlank`, `@Size(max=128)`       | `SEC-USR-PWD PIC X(08)` (extended to 128 for BCrypt) |

Both fields are normalised to uppercase internally to match the COBOL case-insensitive lookup.

**Responses**

`200 OK` — `SignOnResponse`:

```json
{
  "token": null,
  "userType": "ADMIN",
  "userId": "ADMIN001",
  "toTranId": "CA00",
  "toProgram": "COADM01C"
}
```

| Field       | Type           | Description                                              |
|-------------|----------------|----------------------------------------------------------|
| `token`     | string / null  | Reserved for future token-based authentication           |
| `userType`  | `ADMIN`/`USER` | Maps to COBOL `SEC-USR-TYPE` (`A`/`U`)                   |
| `userId`    | string         | Normalised (uppercase) user ID                           |
| `toTranId`  | string         | CICS transaction ID to route to (`CA00` admin, `CM01` user) |
| `toProgram` | string         | COBOL program to route to (`COADM01C` or `COMEN01C`)     |

`400 Bad Request` — `ValidationException` (`VALID`) when the body fails bean validation.

`401 Unauthorized` — Unified for (a) user not found, (b) password mismatch, (c) blank credentials escaping bean validation. Returning a single status deliberately prevents user enumeration.

**Security notes**

- Plaintext passwords are redacted by the request-logging filter (`WebConfig#requestLoggingFilter`) before any log record is emitted.
- The endpoint is rate-limited only at the infrastructure layer (not inside the controller). Deployers SHOULD front the service with a rate limiter such as a reverse proxy or API gateway.

---

## 3. Account Management — `/api/accounts`

COBOL Origin: **COACTVWC.cbl** (view, 941 lines) and **COACTUPC.cbl** (update, 4,236 lines — the most complex program in the original system). CICS transactions `CA00` (view) and `CA01` (update); BMS maps `COACTVW` and `COACTUP`.

### 3.1 View Account — `GET /api/accounts/{id}`

Returns a consolidated `AccountDto` projecting the COBOL "account view" screen. Internally performs the three-dataset read chain `CXACAIX` → `ACCTDAT` → `CUSTDAT` (COBOL paragraphs `9200-GETCARDXREF-BYACCT`, `9300-GETACCTDATA-BYACCT`, `9400-GETCUSTDATA-BYCUST`).

- **Controller**: `AccountController#getAccount`
- **Auth**: any authenticated user
- **Idempotent**: yes (read-only; service uses `@Transactional(readOnly=true)`)

**Request**

| Path param | Type   | Constraints                 | COBOL Source         |
|------------|--------|-----------------------------|----------------------|
| `id`       | string | 11 digits (`PIC 9(11)`)     | `ACCT-ID` in `ACCTDAT` |

No request body.

**Responses**

`200 OK` — `AccountDto` (see Appendix C for the full field list).

Truncated example:

```json
{
  "acctId": "00000000010",
  "acctActiveStatus": "Y",
  "custId": "000000010",
  "acctCurrBal": 1250.75,
  "acctCreditLimit": 5000.00,
  "acctCashCreditLimit": 1000.00,
  "acctCurrCycCredit": 0.00,
  "acctCurrCycDebit": 250.00,
  "acctOpenDate": "2019-04-15",
  "acctExpDate": "2026-04-30",
  "acctReissueDate": "2023-04-15",
  "acctGroupId": "DEFAULT",
  "custFname": "Maybell",
  "custMname": "R",
  "custLname": "Mann",
  "custAddr1": "123 Main St",
  "custCity": "Springfield",
  "custState": "IL",
  "custZip": "62701",
  "custCountry": "USA",
  "custPhone1": "2175551234",
  "custPhone2": "",
  "custSsn": "***-**-1234",
  "custDob": "1985-06-12",
  "custFicoScore": "720",
  "custGovtId": "IL-DL-12345678",
  "custEftAcct": "98765432",
  "custProfileFlag": "Y",
  "stmtNum": "000000042",
  "version": 3
}
```

`400 Bad Request` (`VALID`) — `id` fails format validation.

`404 Not Found` (`RNF`) — account, cross-reference, or customer record missing. The `message` field identifies which record was absent.

### 3.2 Update Account — `PUT /api/accounts/{id}`

Performs the dual-dataset atomic update orchestrated by `COACTUPC.cbl`. The service writes both the `ACCOUNT` and `CUSTOMER` rows inside a single `@Transactional(rollbackFor = Exception.class)` boundary, matching the COBOL `EXEC CICS SYNCPOINT ROLLBACK` behaviour on partial failure.

- **Controller**: `AccountController#updateAccount`
- **COBOL paragraphs**: `1200-EDIT-MAP-INPUTS` (25+ field validations) → `9600-WRITE-PROCESSING` (atomic write) → `9700-CHECK-CHANGE-IN-REC` (optimistic lock check)
- **Auth**: any authenticated user
- **Idempotent**: yes (same payload produces same state provided the `version` token still matches)

**Request**

| Path param | Type   | Constraints            |
|------------|--------|------------------------|
| `id`       | string | 11 digits              |

Body: `AccountDto` (Appendix C). Key validation rules:

- SSN: 9 digits, 3-part structure, area `000`/`666`/`900–999` rejected.
- FICO: 300–850 inclusive.
- State: member of `us-state-codes.json`.
- ZIP: cross-validated against `state-zip-prefixes.json`.
- Phone: NANPA area codes (`nanpa-area-codes.json`).
- Date fields: validated via the `DateValidationService` (equivalent to COBOL `CEEDAYS`/`CSUTLDTC`).
- `version`: optimistic-locking token supplied by the client from the previous GET.

All monetary fields are `BigDecimal` with scale 2 (per Decision D-001 / AAP §0.8.2).

**Responses**

`200 OK` — `AccountDto` reflecting the persisted state with incremented `version`.

`400 Bad Request` (`VALID`) — validation failures; `fieldErrors` lists every offending field.

`404 Not Found` (`RNF`) — target account or customer not found.

`409 Conflict` (`LOCK`) — `ConcurrentModificationException`; another client modified the record since the GET. Clients should re-read and reapply user changes.

---

## 4. Card Management — `/api/cards`

COBOL Origin: **COCRDLIC.cbl** (list, 1,459 lines), **COCRDSLC.cbl** (select/view, 887 lines), **COCRDUPC.cbl** (update, 1,560 lines). CICS transactions `CC01` (list), `CC02` (view), `CC03` (update); BMS maps `COCRDLI`, `COCRDSL`, `COCRDUP`.

**PCI compliance**: Card numbers are masked in all log output (`****1234` shows only the last four digits). CVV (`cardCvvCd`) is annotated `@JsonProperty(access = WRITE_ONLY)` and therefore accepted on request bodies but never serialised into responses.

### 4.1 List Cards — `GET /api/cards`

Paginated browse across the `CARDDAT` dataset. Page size matches the COBOL `WS-MAX-SCREEN-LINES = 7`.

- **Controller**: `CardController#listCards`
- **Auth**: any authenticated user

**Query parameters**

| Param     | Type    | Default | Description                                                                        |
|-----------|---------|---------|------------------------------------------------------------------------------------|
| `page`    | integer | 0       | Zero-based page index (maps COBOL PF7/PF8 navigation).                             |
| `acctId`  | string  | —       | Optional 11-digit account filter. When present, the service uses the `CXACAIX` alternate index (COBOL `9500-FILTER-RECORDS`) and `cardNum` is silently ignored. |
| `cardNum` | string  | —       | Optional 16-digit card filter. Ignored when `acctId` is supplied.                  |

**Responses**

`200 OK` — Spring `Page<CardDto>`:

```json
{
  "content": [
    { "cardNum": "****1234", "cardAcctId": "00000000010", "cardEmbossedName": "MAYBELL R MANN",
      "cardExpDate": "2026-04-30", "cardActiveStatus": "Y", "version": 2 }
  ],
  "pageable": { "pageNumber": 0, "pageSize": 7, "sort": { "sorted": true } },
  "totalElements": 42,
  "totalPages": 6,
  "first": true,
  "last": false,
  "number": 0,
  "size": 7,
  "numberOfElements": 7
}
```

`400 Bad Request` (`VALID`) — format violations on `acctId` or `cardNum`.

### 4.2 List Cards by Account — `GET /api/cards/account/{acctId}`

Non-paginated enumeration of every card for the specified account. Uses the `CARDAIX` alternate index (COBOL paragraph `9150-GETCARD-BYACCT` in `COCRDSLC.cbl`).

- **Controller**: `CardController#getCardsByAccount`
- **Auth**: any authenticated user

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `acctId`   | string | 11 digits   |

**Responses**

`200 OK` — `List<CardDto>` (possibly empty). Card numbers are masked in the response body.

`400 Bad Request` (`VALID`) — invalid `acctId`.

### 4.3 View Card — `GET /api/cards/{cardNum}`

Keyed read on `CARDDAT` (primary key = card number). COBOL paragraph `9100-GETCARD-BYACCTCARD`.

- **Controller**: `CardController#getCard`
- **Auth**: any authenticated user

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `cardNum`  | string | 16 digits (`PIC X(16)`) |

**Responses**

`200 OK` — `CardDto` (CVV omitted from response).

`404 Not Found` (`RNF`) — maps COBOL FILE STATUS 23 / `DFHRESP(NOTFND)`.

### 4.4 Update Card — `PUT /api/cards/{cardNum}`

- **Controller**: `CardController#updateCard`
- **COBOL paragraphs**: `1100-VALIDATE-CARD-DATA` (aggregated validation errors), `1200-CHECK-FOR-CHANGES`, `9300-CHECK-CHANGE-IN-REC` (optimistic lock).
- **Auth**: any authenticated user

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `cardNum`  | string | 16 digits   |

**Request body**: `CardDto`. `cardCvvCd` is accepted and persisted; never echoed back. `version` is required for optimistic locking.

**Responses**

`200 OK` — `CardDto` with refreshed `version`.

`400 Bad Request` (`VALID`) — validation failures; `fieldErrors` aggregates every offender (matching the COBOL policy of reporting all errors before round-tripping).

`404 Not Found` (`RNF`) — card absent.

`409 Conflict` (`LOCK`) — concurrent update detected via JPA `@Version`.

---

## 5. Transaction Management — `/api/transactions`

COBOL Origin: **COTRN00C.cbl** (list, 699 lines), **COTRN01C.cbl** (view, 330 lines), **COTRN02C.cbl** (add, 783 lines). CICS transactions `CT00`, `CT01`, `CT02`; BMS maps `COTRN00`, `COTRN01`, `COTRN02`.

### 5.1 List Transactions — `GET /api/transactions`

- **Controller**: `TransactionController#listTransactions`
- **COBOL paragraphs**: `1000-START-BROWSE`, `1100-READ-NEXT`, `1200-READ-PREV`
- **Auth**: any authenticated user
- **Page size**: 10 (matches COBOL display width)

**Query parameters**

| Param                | Type    | Default | Description                                                           |
|----------------------|---------|---------|-----------------------------------------------------------------------|
| `page`               | integer | 0       | Zero-based page index.                                                |
| `startTransactionId` | string  | —       | Optional lexicographic `>=` filter; maps BMS `TRNIDINI` field.        |

**Responses**

`200 OK` — `Page<TransactionDto>`. Card numbers masked.

### 5.2 View Transaction — `GET /api/transactions/{id}`

- **Controller**: `TransactionController#getTransaction`
- **COBOL paragraphs**: `2000-READ-TRANSACTION`, `3000-SEND-MAP`
- **Auth**: any authenticated user

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `id`       | string | 16 chars (`PIC X(16)`) |

**Responses**

`200 OK` — `TransactionDto`.

`404 Not Found` (`RNF`) — transaction absent (COBOL FILE STATUS 23).

### 5.3 Add Transaction — `POST /api/transactions`

Creates a new transaction. The transaction ID is always generated server-side (COBOL `4100-GENERATE-TRAN-ID`); any value supplied in the request body is ignored.

- **Controller**: `TransactionController#addTransaction`
- **COBOL paragraphs**: `4100-GENERATE-TRAN-ID` → `4200-RESOLVE-XREF` → `VALIDATE-INPUT-DATA-FIELDS` (lines 330–498) → `4300-WRITE-TRANSACTION`
- **Auth**: any authenticated user

**Request body**: `TransactionDto` (see Appendix C). Key rules:

- `tranAmt` is `BigDecimal` with 2-decimal scale (`PIC S9(09)V99 COMP-3`).
- Either `tranCardNum` or the account reference must resolve through `CARDXREF` (`4200-RESOLVE-XREF`).
- Merchant and description fields are bounded by `@Size` annotations that mirror the COBOL `PIC` lengths.
- Transaction type, category, and source codes are validated against reference tables.

**Responses**

`201 Created` — persisted `TransactionDto` with server-generated `tranId`, `tranProcTs`, and normalised fields.

`400 Bad Request` (`VALID`) — any field validation failure.

`404 Not Found` (`RNF`) — cross-reference lookup failed (card or account unknown).

`409 Conflict` (`DUP`) — rare race condition where the generated ID collides with an in-flight concurrent insert (maps COBOL FILE STATUS 22).

### 5.4 Copy Transaction — `GET /api/transactions/copy/{sourceId}`

Convenience endpoint that reads an existing transaction and returns a copy suitable for pre-populating a new-transaction form. The COBOL equivalent is the `PF5` "copy" feature on the `COTRN00` list screen. The returned DTO has `tranId` set to `null` so the client can POST it back through §5.3 and receive a freshly generated ID.

- **Controller**: `TransactionController#copyTransaction`
- **Auth**: any authenticated user

| Path param  | Type   | Constraints |
|-------------|--------|-------------|
| `sourceId`  | string | 16 chars (`PIC X(16)`) |

**Responses**

`200 OK` — `TransactionDto` with `tranId = null`, `tranOrigTs = null`, `tranProcTs = null`.

`404 Not Found` (`RNF`) — source transaction absent.

---

## 6. Billing — `/api/billing`

COBOL Origin: **COBIL00C.cbl** (572 lines). CICS transaction `CB00`, BMS map `COBIL00`.

### 6.1 Pay Bill — `POST /api/billing/pay`

Pays the full outstanding balance on an account, creating a payment transaction and decrementing `ACCT-CURR-BAL` atomically. When the request body's `confirmIndicator` is not `"Y"`, the endpoint returns a cancellation acknowledgment without side effects — this preserves the COBOL two-step confirm pattern inside a stateless REST call.

- **Controller**: `BillingController#payBill`
- **COBOL paragraphs**: `MAIN-PARA` (101–152) → `PROCESS-ENTER-KEY` (154–244) → `READ-ACCTDAT-FILE` (343–372) → `READ-CXACAIX-FILE` (408–436) → `STARTBR-TRANSACT-FILE` (441–467) → `READPREV-TRANSACT-FILE` (472–496) → `ENDBR-TRANSACT-FILE` (501–505) → `WRITE-TRANSACT-FILE` (510–547) → `UPDATE-ACCTDAT-FILE` (377–403)
- **Auth**: any authenticated user

**Request body**: `BillPaymentRequest`

```json
{
  "accountId": "00000000010",
  "confirmIndicator": "Y"
}
```

| Field              | Type   | Required | Constraints                                      | COBOL Source |
|--------------------|--------|----------|--------------------------------------------------|--------------|
| `accountId`        | string | yes      | `@NotBlank`, `@Size(max=11)`                     | BMS `ACTIDINI PIC X(11)` |
| `confirmIndicator` | string | no       | `@Size(max=1)`, `@Pattern(regexp="^[YN]?$")`     | BMS `CONFIRMI PIC X(1)` |

The pattern `^[YN]?$` accepts empty string, `Y`, or `N` only. Case-insensitive comparison is performed in the controller (`"Y".equalsIgnoreCase(confirmIndicator)`).

**Responses**

`200 OK` — cancellation acknowledgment. Body:

```json
{
  "message": "Bill payment cancelled"
}
```

`201 Created` — payment processed successfully. Body is the created `Transaction` entity with hardcoded COBOL values:

```json
{
  "tranId": "0000000000042001",
  "tranTypeCd": "02",
  "tranCatCd": "0002",
  "tranSource": "POS TERM",
  "tranDesc": "BILL PAYMENT - ONLINE",
  "tranAmt": -1250.75,
  "tranCardNum": "****1234",
  "tranMerchId": "999999999",
  "tranMerchName": "BILL PAYMENT",
  "tranMerchCity": "",
  "tranMerchZip": "",
  "tranOrigTs": "2026-03-17T10:15:30",
  "tranProcTs": "2026-03-17T10:15:30"
}
```

`400 Bad Request` (`VALID`) — validation failures.

`404 Not Found` (`RNF`) — account or cross-reference missing.

`422 Unprocessable Entity` (`CREDIT`) — payment would violate credit rules (rare for bill-payment; COBOL reject code 102).

**Transactional semantics**: The entire payment cycle (balance read → validation → transaction write → balance update) executes inside a single `@Transactional` boundary; a failure at any step rolls the whole transaction back, preserving the COBOL `EXEC CICS SYNCPOINT ROLLBACK` guarantee.

**Logging**: `accountId` is considered low-risk and is logged; `tranAmt`, balances, and other financial values are NEVER logged.

---

## 7. Reports — `/api/reports`

COBOL Origin: **CORPT00C.cbl** (649 lines). BMS map `CORPT00`.

### 7.1 Submit Report — `POST /api/reports/submit`

Queues a report generation job for asynchronous batch processing. This endpoint preserves the COBOL "online-to-batch" bridge: the online program (`CORPT00C`) originally wrote to the CICS TDQ `'JOBS'` queue via `SUBMIT-JOB-TO-INTRDR`/`WIRTE-JOBSUB-TDQ`; the REST replacement publishes the request to the SQS FIFO queue `carddemo-report-jobs.fifo`, where the batch pipeline picks it up.

- **Controller**: `ReportController#submitReport`
- **COBOL paragraphs**: `MAIN-PARA` (163–202) → `PROCESS-ENTER-KEY` (208–456) → `SUBMIT-JOB-TO-INTRDR` (462–510) → `WIRTE-JOBSUB-TDQ` (515–535) → `SEND-TRNRPT-SCREEN` (556–580) / `RETURN-TO-PREV-SCREEN` (540–551)
- **Auth**: any authenticated user

**Request body**: `ReportRequest`

```json
{
  "monthly": true,
  "yearly": false,
  "custom": false,
  "startDate": "2026-02-01",
  "endDate":   "2026-02-28",
  "confirm":   "Y"
}
```

| Field       | Type       | Required                       | Constraints                         | COBOL Source |
|-------------|------------|--------------------------------|-------------------------------------|--------------|
| `monthly`   | boolean    | exactly one of M/Y/C is `true` | —                                   | BMS `MONTHLY` |
| `yearly`    | boolean    | exactly one of M/Y/C is `true` | —                                   | BMS `YEARLY` |
| `custom`    | boolean    | exactly one of M/Y/C is `true` | —                                   | BMS `CUSTOM` |
| `startDate` | LocalDate  | required if `custom = true`    | ISO-8601 (`yyyy-MM-dd`)             | BMS `SDTMMI/SDTDDI/SDTYYI` |
| `endDate`   | LocalDate  | required if `custom = true`    | ISO-8601; must be `>= startDate`    | BMS `EDTMMI/EDTDDI/EDTYYI` |
| `confirm`   | string     | yes                            | `@Size(max=1)`                      | BMS `CONFIRMI PIC X(1)` |

Validation additionally enforces "exactly one of `monthly`/`yearly`/`custom` is true" — otherwise `ValidationException` (`VALID`) is raised.

**Responses**

`200 OK` — cancellation when `confirm` is not `"Y"`. Body: `"Report submission cancelled"` (text).

`202 Accepted` — report queued successfully. Body is a human-readable confirmation string (not an SQS message ID), e.g. `"Monthly report submitted for printing 2026-02"`. The `202` status is architecturally significant: it mirrors the COBOL "fire and forget" TDQ submission and signals to the client that generation is asynchronous.

`400 Bad Request` (`VALID`) — validation failure (missing dates for custom range, `endDate < startDate`, multiple booleans set, etc.).

`500 Internal Server Error` (`CARDDEMO_ERROR`) — SQS publish failure (downstream AWS unavailability).

**Operational note**: The `application.yml` property `carddemo.report.queue-name` controls which SQS queue is used. The queue MUST be FIFO (the contract requires ordered processing of report submissions); deployers that override this property for local testing should ensure the target queue's `FifoQueue` attribute is `true`.

---

## 8. User Administration — `/api/admin/users`

COBOL Origin: **COUSR00C.cbl** (list, 695 lines), **COUSR01C.cbl** (add, 299 lines), **COUSR02C.cbl** (view/update, 414 lines), **COUSR03C.cbl** (delete, 359 lines). CICS transactions `CU00` (list), `CU01` (add), `CU02` (view/update), `CU03` (delete).

**Authorization**: every endpoint in this section is gated by `hasRole('ADMIN')` in `SecurityConfig`. A non-admin authenticated request receives `403 Forbidden` with `AccessDeniedException`.

### 8.1 List Users — `GET /api/admin/users`

- **Controller**: `UserAdminController#listUsers`
- **COBOL paragraphs**: `PROCESS-PAGE-FORWARD`, `PROCESS-PF7-KEY` (backward), `PROCESS-PF8-KEY` (forward)
- **Auth**: `ROLE_ADMIN`
- **Page size**: 10 (matches COBOL `WS-USER-DATA OCCURS 10`)

**Query parameters**

| Param         | Type    | Default | Description                                                              |
|---------------|---------|---------|--------------------------------------------------------------------------|
| `page`        | integer | 0       | Zero-based page index                                                    |
| `startUserId` | string  | —       | Optional lexicographic `>=` filter (maps BMS `USRIDINI` field in `COUSR00C`) |

**Responses**

`200 OK` — `Page<UserSecurityDto>`. Each item omits the `secUsrPwd` field (`WRITE_ONLY`).

### 8.2 View User — `GET /api/admin/users/{id}`

- **Controller**: `UserAdminController#getUser`
- **COBOL paragraphs**: `PROCESS-ENTER-KEY` in `COUSR02C.cbl`
- **Auth**: `ROLE_ADMIN`

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `id`       | string | up to 8 chars (`SEC-USR-ID PIC X(8)`) |

**Responses**

`200 OK` — `UserSecurityDto` (password omitted).

`400 Bad Request` (`VALID`) — invalid user ID format.

`404 Not Found` (`RNF`) — user absent.

### 8.3 Add User — `POST /api/admin/users`

- **Controller**: `UserAdminController#addUser`
- **COBOL paragraphs**: `PROCESS-ENTER-KEY`, `WRITE-USER-SEC-FILE` in `COUSR01C.cbl`
- **Auth**: `ROLE_ADMIN`

**Request body**: `UserSecurityDto` (all five fields required). Validation order matches COBOL: `FNAME` → `LNAME` → `USERID` → `PASSWORD` → `USERTYPE`. The plaintext password is hashed with BCrypt before persisting (per Decision D-002).

**Responses**

`201 Created` — persisted `UserSecurityDto` (password field absent from the response).

`400 Bad Request` (`VALID`) — validation failure.

`409 Conflict` (`DUP`) — user ID already exists. The `message` field is the literal COBOL text `"User ID already exist..."` for parity.

### 8.4 Update User — `PUT /api/admin/users/{id}`

- **Controller**: `UserAdminController#updateUser`
- **COBOL paragraphs**: `UPDATE-USER-INFO`, `UPDATE-USER-SEC-FILE` in `COUSR02C.cbl`
- **Auth**: `ROLE_ADMIN`

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `id`       | string | up to 8 chars (`SEC-USR-ID PIC X(8)`) |

**Request body**: `UserSecurityDto`. When `secUsrPwd` is supplied and differs from the persisted BCrypt hash (after comparison), the service re-hashes with BCrypt. When every field matches the persisted record, the service throws `ValidationException` with the COBOL-parity message `"Please modify to update..."`.

**Responses**

`200 OK` — persisted `UserSecurityDto`.

`400 Bad Request` (`VALID`) — validation failure or the no-change guard.

`404 Not Found` (`RNF`) — user absent.

### 8.5 Delete User — `DELETE /api/admin/users/{id}`

- **Controller**: `UserAdminController#deleteUser`
- **COBOL paragraphs**: `DELETE-USER-INFO`, `DELETE-USER-SEC-FILE` in `COUSR03C.cbl`
- **Auth**: `ROLE_ADMIN`

| Path param | Type   | Constraints |
|------------|--------|-------------|
| `id`       | string | up to 8 chars |

The COBOL program used a two-screen pattern (`COUSR02` display → `COUSR03` confirm → delete). The REST equivalent is the client's responsibility: perform GET §8.2 to display user details, then DELETE when the operator confirms.

**Responses**

`204 No Content` — delete successful.

`400 Bad Request` (`VALID`) — invalid user ID format.

`404 Not Found` (`RNF`) — user absent.

---

## 9. Menu Navigation — `/api/menu`

The menu endpoint replaces the COBOL main-menu programs `COMEN01C.cbl` (user main menu, 282 lines) and `COADM01C.cbl` (administrator menu, 268 lines). Both programs share the paragraph `BUILD-MENU-OPTIONS`; the Java implementation is split across `MainMenuService` and `AdminMenuService`, then fronted by a single `MenuController` with a path parameter.

**Single endpoint, two sub-contracts**: `GET /api/menu/{type}` dispatches on a path parameter. §9.1 documents the main-menu sub-contract; §9.2 documents the admin-menu sub-contract. This consolidation is deliberate and intentional — see D-011 in `DECISION_LOG.md`.

### 9.1 Main Menu — `GET /api/menu/main`

Returns the 10-option main menu rendered by `COMEN01C.cbl` from the copybook `COMEN02Y.cpy`.

- **Controller**: `MenuController#getMenu` with `type="main"`
- **Auth**: any authenticated user

**Responses**

`200 OK` — `List<MainMenuService.MenuOption>`. Example:

```json
[
  { "optionNumber": 1,  "optionName": "Account View",         "optionKey": "CAVW", "targetTransaction": "CA00", "targetProgram": "COACTVWC" },
  { "optionNumber": 2,  "optionName": "Account Update",       "optionKey": "CAUP", "targetTransaction": "CA01", "targetProgram": "COACTUPC" },
  { "optionNumber": 3,  "optionName": "Credit Card List",     "optionKey": "CCLI", "targetTransaction": "CC01", "targetProgram": "COCRDLIC" },
  { "optionNumber": 4,  "optionName": "Credit Card View",     "optionKey": "CCVW", "targetTransaction": "CC02", "targetProgram": "COCRDSLC" },
  { "optionNumber": 5,  "optionName": "Credit Card Update",   "optionKey": "CCUP", "targetTransaction": "CC03", "targetProgram": "COCRDUPC" },
  { "optionNumber": 6,  "optionName": "Transaction List",     "optionKey": "CTLI", "targetTransaction": "CT00", "targetProgram": "COTRN00C" },
  { "optionNumber": 7,  "optionName": "Transaction View",     "optionKey": "CTVW", "targetTransaction": "CT01", "targetProgram": "COTRN01C" },
  { "optionNumber": 8,  "optionName": "Transaction Add",      "optionKey": "CTAD", "targetTransaction": "CT02", "targetProgram": "COTRN02C" },
  { "optionNumber": 9,  "optionName": "Transaction Reports",  "optionKey": "CTRP", "targetTransaction": "CR00", "targetProgram": "CORPT00C" },
  { "optionNumber": 10, "optionName": "Bill Payment",         "optionKey": "CBIL", "targetTransaction": "CB00", "targetProgram": "COBIL00C" }
]
```

### 9.2 Admin Menu — `GET /api/menu/admin`

Returns the 4-option admin menu rendered by `COADM01C.cbl` from the copybook `COADM02Y.cpy`.

- **Controller**: `MenuController#getMenu` with `type="admin"`
- **Auth**: `ROLE_ADMIN` (enforced inside the controller by `isAdmin()` checking `SecurityContextHolder` for `ROLE_ADMIN`; matches COBOL `CDEMO-USRTYPE = 'A'`).

**Responses**

`200 OK` — `List<AdminMenuService.AdminMenuOption>`. Example:

```json
[
  { "optionNumber": 1, "optionName": "User List",   "optionKey": "CUSR0", "targetTransaction": "CU00", "targetProgram": "COUSR00C" },
  { "optionNumber": 2, "optionName": "User Add",    "optionKey": "CUSR1", "targetTransaction": "CU01", "targetProgram": "COUSR01C" },
  { "optionNumber": 3, "optionName": "User Update", "optionKey": "CUSR2", "targetTransaction": "CU02", "targetProgram": "COUSR02C" },
  { "optionNumber": 4, "optionName": "User Delete", "optionKey": "CUSR3", "targetTransaction": "CU03", "targetProgram": "COUSR03C" }
]
```

`400 Bad Request` (`VALID`) — `type` is neither `main` nor `admin`. The `message` field is the literal text `"Invalid menu type. Use 'main' or 'admin'."`.

`403 Forbidden` — a non-admin authenticated user requested `type = admin`. This is raised inside the controller via `AccessDeniedException`, then mapped to the `ErrorResponse` by the global handler. The contract's mandate of `403` (rather than `401` or `404`) is explicitly required; see the reference from `MenuController.java` line 121.

---

## Appendix A — Endpoint Index (Alphabetical)

| Path                                    | Method | Section |
|-----------------------------------------|--------|---------|
| `/api/accounts/{id}`                    | GET    | §3.1    |
| `/api/accounts/{id}`                    | PUT    | §3.2    |
| `/api/admin/users`                      | GET    | §8.1    |
| `/api/admin/users`                      | POST   | §8.3    |
| `/api/admin/users/{id}`                 | DELETE | §8.5    |
| `/api/admin/users/{id}`                 | GET    | §8.2    |
| `/api/admin/users/{id}`                 | PUT    | §8.4    |
| `/api/auth/signin`                      | POST   | §2.1    |
| `/api/billing/pay`                      | POST   | §6.1    |
| `/api/cards`                            | GET    | §4.1    |
| `/api/cards/account/{acctId}`           | GET    | §4.2    |
| `/api/cards/{cardNum}`                  | GET    | §4.3    |
| `/api/cards/{cardNum}`                  | PUT    | §4.4    |
| `/api/menu/{type}` (`type=admin`)       | GET    | §9.2    |
| `/api/menu/{type}` (`type=main`)        | GET    | §9.1    |
| `/api/reports/submit`                   | POST   | §7.1    |
| `/api/transactions`                     | GET    | §5.1    |
| `/api/transactions`                     | POST   | §5.3    |
| `/api/transactions/copy/{sourceId}`     | GET    | §5.4    |
| `/api/transactions/{id}`                | GET    | §5.2    |

Total: **19 endpoints**.

## Appendix B — COBOL to REST Traceability

| COBOL Program    | CICS TRX | BMS Map   | REST Endpoint(s)                                              | Section |
|------------------|----------|-----------|---------------------------------------------------------------|---------|
| COSGN00C         | CC00     | COSGN00   | `POST /api/auth/signin`                                       | §2.1    |
| COACTVWC         | CA00     | COACTVW   | `GET /api/accounts/{id}`                                      | §3.1    |
| COACTUPC         | CA01     | COACTUP   | `PUT /api/accounts/{id}`                                      | §3.2    |
| COCRDLIC         | CC01     | COCRDLI   | `GET /api/cards`                                              | §4.1    |
| COCRDSLC         | CC02     | COCRDSL   | `GET /api/cards/{cardNum}`, `GET /api/cards/account/{acctId}` | §4.3, §4.2 |
| COCRDUPC         | CC03     | COCRDUP   | `PUT /api/cards/{cardNum}`                                    | §4.4    |
| COTRN00C         | CT00     | COTRN00   | `GET /api/transactions`                                       | §5.1    |
| COTRN01C         | CT01     | COTRN01   | `GET /api/transactions/{id}`                                  | §5.2    |
| COTRN02C         | CT02     | COTRN02   | `POST /api/transactions`, `GET /api/transactions/copy/{id}`   | §5.3, §5.4 |
| COBIL00C         | CB00     | COBIL00   | `POST /api/billing/pay`                                       | §6.1    |
| CORPT00C         | CR00     | CORPT00   | `POST /api/reports/submit`                                    | §7.1    |
| COUSR00C         | CU00     | COUSR00   | `GET /api/admin/users`                                        | §8.1    |
| COUSR01C         | CU01     | COUSR01   | `POST /api/admin/users`                                       | §8.3    |
| COUSR02C         | CU02     | COUSR02   | `GET /api/admin/users/{id}`, `PUT /api/admin/users/{id}`      | §8.2, §8.4 |
| COUSR03C         | CU03     | COUSR03   | `DELETE /api/admin/users/{id}`                                | §8.5    |
| COMEN01C         | CM01     | COMEN01   | `GET /api/menu/{type}` (`type=main`)                          | §9.1    |
| COADM01C         | CA00\*   | COADM01   | `GET /api/menu/{type}` (`type=admin`)                         | §9.2    |

\* `COADM01C` shares CICS TRX `CA00` routing metadata via the sign-on response (`toTranId=CA00`, `toProgram=COADM01C` for admin users).

Full paragraph-level mappings appear in `TRACEABILITY_MATRIX.md`.

## Appendix C — DTO Field Reference

This appendix documents every field on every DTO that appears in the request/response bodies above. Where a field carries validation annotations, they are listed verbatim from the Java source.

### C.1 SignOnRequest

| Field      | Type   | Validation                        |
|------------|--------|-----------------------------------|
| `userId`   | string | `@NotBlank`, `@Size(max=8)`       |
| `password` | string | `@NotBlank`, `@Size(max=128)`     |

### C.2 SignOnResponse

| Field       | Type             | Notes                                          |
|-------------|------------------|------------------------------------------------|
| `token`     | string / null    | Reserved for future use; always `null` today   |
| `userType`  | `UserType` enum  | `ADMIN` or `USER`                              |
| `userId`    | string           | Normalised (uppercase)                         |
| `toTranId`  | string           | CICS transaction ID (`CA00` admin, `CM01` user) |
| `toProgram` | string           | COBOL program (`COADM01C` or `COMEN01C`)       |

### C.3 AccountDto

27 fields representing the consolidated COBOL `ACCT-RECORD` + `CUST-RECORD` view. Monetary fields are `BigDecimal` with scale 2.

| Field                      | Type         | Validation                  | COBOL Source                    |
|----------------------------|--------------|-----------------------------|---------------------------------|
| `acctId`                   | string       | `@Size(max=11)`             | `ACCT-ID PIC 9(11)`             |
| `acctActiveStatus`         | string       | `@Size(max=1)`              | `ACCT-ACTIVE-STATUS`            |
| `custId`                   | string       | `@Size(max=9)`              | `CUST-ID PIC 9(09)`             |
| `acctCurrBal`              | BigDecimal   | —                           | `ACCT-CURR-BAL`                 |
| `acctCreditLimit`          | BigDecimal   | —                           | `ACCT-CREDIT-LIMIT`             |
| `acctCashCreditLimit`      | BigDecimal   | —                           | `ACCT-CASH-CREDIT-LIMIT`        |
| `acctCurrCycCredit`        | BigDecimal   | —                           | `ACCT-CURR-CYC-CREDIT`          |
| `acctCurrCycDebit`         | BigDecimal   | —                           | `ACCT-CURR-CYC-DEBIT`           |
| `acctOpenDate`             | LocalDate    | —                           | `ACCT-OPEN-DATE`                |
| `acctExpDate`              | LocalDate    | —                           | `ACCT-EXPIRAION-DATE`           |
| `acctReissueDate`          | LocalDate    | —                           | `ACCT-REISSUE-DATE`             |
| `acctGroupId`              | string       | `@Size(max=10)`             | `ACCT-GROUP-ID`                 |
| `custFname`                | string       | `@Size(max=25)`             | `CUST-FIRST-NAME`               |
| `custMname`                | string       | `@Size(max=25)`             | `CUST-MIDDLE-NAME`              |
| `custLname`                | string       | `@Size(max=25)`             | `CUST-LAST-NAME`                |
| `custAddr1`                | string       | `@Size(max=50)`             | `CUST-ADDR-LINE-1`              |
| `custAddr2`                | string       | `@Size(max=50)`             | `CUST-ADDR-LINE-2`              |
| `custCity`                 | string       | `@Size(max=50)`             | `CUST-ADDR-CITY`                |
| `custState`                | string       | `@Size(max=2)`              | `CUST-ADDR-STATE-CD`            |
| `custZip`                  | string       | `@Size(max=10)`             | `CUST-ADDR-ZIP`                 |
| `custCountry`              | string       | `@Size(max=3)`              | `CUST-ADDR-COUNTRY-CD`          |
| `custPhone1`               | string       | `@Size(max=13)`             | `CUST-PHONE-NUM-1`              |
| `custPhone2`               | string       | `@Size(max=13)`             | `CUST-PHONE-NUM-2`              |
| `custSsn`                  | string       | `@Size(max=12)` (PII; redacted in `toString`) | `CUST-SSN`   |
| `custDob`                  | LocalDate    | —                           | `CUST-DOB-YYYY-MM-DD`           |
| `custFicoScore`            | string       | `@Size(max=3)`              | `CUST-FICO-CREDIT-SCORE` (300–850) |
| `custGovtId`               | string       | `@Size(max=20)`             | `CUST-GOVT-ISSUED-ID`           |
| `custEftAcct`              | string       | `@Size(max=10)`             | `CUST-EFT-ACCOUNT-ID`           |
| `custProfileFlag`          | string       | `@Size(max=1)`              | `CUST-PRI-CARD-HOLDER-IND`      |
| `stmtNum`                  | string       | `@Size(max=9)`              | `CUST-PRI-HLDR-IND` / stmt num  |
| `version`                  | Integer      | —                           | JPA `@Version` (optimistic locking token) |

### C.4 CardDto

| Field              | Type         | Validation                                                | COBOL Source                                     |
|--------------------|--------------|-----------------------------------------------------------|--------------------------------------------------|
| `cardNum`          | string       | `@Size(max=16)`; masked in responses via `CardNumberMaskingSerializer` | `CARD-NUM PIC X(16)` (PCI-sensitive) |
| `cardAcctId`       | string       | `@Size(max=11)`                                           | `CARD-ACCT-ID`                                   |
| `cardEmbossedName` | string       | `@Size(max=50)`                                           | `CARD-EMBOSSED-NAME`                             |
| `cardExpDate`      | LocalDate    | —                                                         | Combined from `CARD-EXPIRAION-YYYY/MM`           |
| `cardActiveStatus` | string       | `@Size(max=1)`                                            | `CARD-ACTIVE-STATUS` (`Y`/`N`)                   |
| `cardCvvCd`        | string       | `@Size(max=4)`, `@JsonProperty(access = WRITE_ONLY)`      | `CARD-CVV-CD` (NEVER returned in responses)      |
| `version`          | Integer      | —                                                         | JPA `@Version` (optimistic locking token)        |

### C.5 TransactionDto

| Field           | Type            | Validation                                                        | COBOL Source                         |
|-----------------|-----------------|-------------------------------------------------------------------|--------------------------------------|
| `tranId`        | string          | `@Size(max=16)`                                                   | `TRAN-ID PIC X(16)` (auto-generated on POST) |
| `tranTypeCd`    | string          | `@Size(max=2)`                                                    | `TRAN-TYPE-CD PIC X(02)`             |
| `tranCatCd`     | string          | `@Size(max=4)`                                                    | `TRAN-CAT-CD PIC X(04)`              |
| `tranSource`    | string          | `@Size(max=10)`                                                   | `TRAN-SOURCE PIC X(10)`              |
| `tranDesc`      | string          | `@Size(max=100)`                                                  | `TRAN-DESC PIC X(100)`               |
| `tranAmt`       | BigDecimal      | scale 2                                                           | `TRAN-AMT PIC S9(09)V99 COMP-3`      |
| `tranCardNum`   | string          | `@Size(max=16)`; masked via `CardNumberMaskingSerializer`         | `TRAN-CARD-NUM PIC X(16)`            |
| `tranMerchId`   | string          | `@Size(max=9)`                                                    | `TRAN-MERCHANT-ID PIC 9(09)`         |
| `tranMerchName` | string          | `@Size(max=50)`                                                   | `TRAN-MERCHANT-NAME PIC X(50)`       |
| `tranMerchCity` | string          | `@Size(max=50)`                                                   | `TRAN-MERCHANT-CITY PIC X(50)`       |
| `tranMerchZip`  | string          | `@Size(max=10)`                                                   | `TRAN-MERCHANT-ZIP PIC X(10)`        |
| `tranOrigTs`    | LocalDateTime   | —                                                                 | `TRAN-ORIG-TS`                       |
| `tranProcTs`    | LocalDateTime   | —                                                                 | `TRAN-PROC-TS`                       |

### C.6 BillPaymentRequest

| Field              | Type   | Validation                                     | COBOL Source             |
|--------------------|--------|------------------------------------------------|--------------------------|
| `accountId`        | string | `@NotBlank`, `@Size(max=11)`                   | BMS `ACTIDINI PIC X(11)` |
| `confirmIndicator` | string | `@Size(max=1)`, `@Pattern(regexp="^[YN]?$")`   | BMS `CONFIRMI PIC X(1)`  |

### C.7 ReportRequest

| Field       | Type       | Validation                                      | COBOL Source                 |
|-------------|------------|-------------------------------------------------|------------------------------|
| `monthly`   | boolean    | exactly one of `monthly`/`yearly`/`custom` true | BMS `MONTHLY`                |
| `yearly`    | boolean    | exactly one of `monthly`/`yearly`/`custom` true | BMS `YEARLY`                 |
| `custom`    | boolean    | exactly one of `monthly`/`yearly`/`custom` true | BMS `CUSTOM`                 |
| `startDate` | LocalDate  | required if `custom=true`                       | BMS `SDTMMI/SDTDDI/SDTYYI`   |
| `endDate`   | LocalDate  | required if `custom=true`; `>= startDate`       | BMS `EDTMMI/EDTDDI/EDTYYI`   |
| `confirm`   | string     | `@Size(max=1)`                                  | BMS `CONFIRMI`               |

### C.8 UserSecurityDto

| Field         | Type              | Validation                                                                                   | COBOL Source         |
|---------------|-------------------|----------------------------------------------------------------------------------------------|----------------------|
| `secUsrId`    | string            | `@Size(max=8)`, `@Pattern(regexp="^[A-Za-z0-9]+$")` — alphanumeric only                      | `SEC-USR-ID PIC X(8)` |
| `secUsrFname` | string            | `@Size(max=20)`, `@Pattern(regexp="^[\\p{L}\\p{N}\\s.'-]*$")` — blocks HTML/script injection | `SEC-USR-FNAME PIC X(20)` |
| `secUsrLname` | string            | `@Size(max=20)`, `@Pattern(regexp="^[\\p{L}\\p{N}\\s.'-]*$")`                                | `SEC-USR-LNAME PIC X(20)` |
| `secUsrPwd`   | string            | `@Size(max=128)`, `@JsonProperty(access=WRITE_ONLY)` — never logged or returned              | `SEC-USR-PWD PIC X(08)` (extended for BCrypt) |
| `secUsrType`  | `UserType` enum   | `ADMIN` (`A`) or `USER` (`U`)                                                                | `SEC-USR-TYPE PIC X(1)` |

### C.9 ErrorResponse

See §1.6. The record is declared in `com.cardemo.config.GlobalExceptionHandler$ErrorResponse` and is the response body for every `4xx` and `5xx` handled by the global handler.
