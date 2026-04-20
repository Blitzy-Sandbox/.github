# Validation Gates — CardDemo

**Document Version**: 1.0.0  
**Last Updated**: 2026-04-20  
**Scope**: Authoritative evidence documentation for all 8 formal validation gates defined in AAP §0.7.2 and enforced by `src/test/java/com/cardemo/e2e/GateVerificationTest.java` and the Maven JaCoCo plugin.

---

## 1. Purpose

This document is the authoritative reference for the **8 formal validation gates** that govern CardDemo's quality assurance posture. Each gate exists to verify a specific dimension of the COBOL→Java migration:

- **Behavioral parity** with the original 28 COBOL programs
- **Runtime correctness** across 18 online transactions (F-001–F-017) and 10 batch programs
- **Production readiness** against enterprise standards (code quality, security, observability, performance)
- **API contract stability** for all 8 REST controllers and their endpoints
- **Scope coverage** across 7 subsystems (batch pipeline, data file loading, online programs, service layer, AWS integration, repository layer, observability)

The gates are **non-negotiable acceptance criteria**. All 8 gates must pass for the codebase to be considered production-ready. Gate failures are blocking regressions and must be remediated before a release candidate is accepted.

### Authoritative Sources

| Artifact                                                                                  | Role                                                                      |
|-------------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| `src/test/java/com/cardemo/e2e/GateVerificationTest.java`                                  | Primary runtime verifier for Gates 1–8 (ordered via `@Order(N)`)           |
| `pom.xml` (JaCoCo `<execution id="check">` block)                                          | Build-time enforcement of ≥80% **LINE** coverage at BUNDLE scope           |
| `pom.xml` (OWASP `<plugin>` block)                                                         | Build-time enforcement of zero Critical/High CVEs                          |
| `TRACEABILITY_MATRIX.md`                                                                    | Manual-review artifact mapping 28 COBOL programs → Java classes           |
| `DECISION_LOG.md` (D-001 through D-019)                                                    | Architectural decisions that bound gate-level acceptance criteria         |
| `docs/api-contracts.md`                                                                    | REST endpoint contract stability reference (Gate 5)                        |
| `docs/architecture-before-after.md`                                                        | Architecture documentation reflecting layered structure                    |
| `docs/onboarding-guide.md`                                                                 | Developer-facing gate documentation and build/test commands                |

### AAP Alignment

Per AAP §0.8.1 R-005 ("Validation gate compliance — All 8 gates in `docs/validation-gates.md` continue to pass"), the gates themselves and their pass criteria are IN SCOPE for this document but are NOT modified by any refactoring. The refactoring task's success is measured, in part, by these gates continuing to pass.

---

## 2. Gate Summary

| #  | Gate Name                                       | Enforcement Mechanism                               | Current Status |
|----|-------------------------------------------------|-----------------------------------------------------|----------------|
| G1 | End-to-End Boundary Verification                | `GateVerificationTest.gate1_*` @Order(1)            | ✅ PASS         |
| G2 | Zero-Warning Build Verification                 | `GateVerificationTest.gate2_*` + `mvn -Werror`      | ✅ PASS         |
| G3 | Performance Baseline                            | `GateVerificationTest.gate3_*` @Order(3)            | ✅ PASS         |
| G4 | Named Real-World Validation Artifacts           | `GateVerificationTest.gate4_*` @Order(4)            | ✅ PASS         |
| G5 | API / Interface Contract Verification           | `GateVerificationTest.gate5_*` @Order(5)            | ✅ PASS         |
| G6 | Unsafe / Low-Level Code Audit                   | `GateVerificationTest.gate6_*` @Order(6)            | ✅ PASS         |
| G7 | Scope Matching — Extended                       | `GateVerificationTest.gate7_*` @Order(7)            | ✅ PASS         |
| G8 | Integration Sign-Off Checklist                  | `GateVerificationTest.gate8_*` @Order(8)            | ✅ PASS         |
| —  | **Code Coverage (AAP-enforced quality metric)** | `pom.xml` JaCoCo `<check>` rule (BUNDLE/LINE/0.80)   | ✅ PASS (80.71%) |
| —  | **OWASP CVE Audit (AAP-enforced security metric)** | `pom.xml` OWASP plugin (zero Critical/High CVEs)   | ✅ PASS         |

> **Note**: Code coverage and OWASP CVE audit are **AAP-enforced production-readiness requirements** that complement the 8 gates. They are verified at build time via Maven plugins, not via `GateVerificationTest.java`. Gate 8 consolidates these into the integration sign-off checklist.

---

## 3. Gate Specifications

### Gate 1 — End-to-End Boundary Verification

**AAP Reference**: §0.7.2 Gate 1 — Boundary verification for the POSTTRAN → INTCALC → COMBTRAN → STMTGEN+TXNRPT batch pipeline.

**Implementation**: `GateVerificationTest.gate1_endToEndBoundaryVerification()` at `@Order(1)` in `src/test/java/com/cardemo/e2e/GateVerificationTest.java`.

**Purpose**: Verify that the complete 5-stage batch pipeline produces deterministic, byte-identical outputs for a fixed input dataset. This is the primary behavioral-parity gate covering Phase 2 of the COBOL migration (batch programs CBTRN01C, CBTRN02C, CBTRN03C, CBACT04C, CBSTM03A/B).

**Implementation Strategy**: The gate delegates the full-pipeline execution to `DailyTransactionPostingJobIT` (the authoritative Phase 2 integration test) and then asserts the ending-state invariants. This avoids duplicating the job orchestration setup while preserving the boundary-verification semantics.

**Pass Criteria**:

| Sub-Criterion                                                         | Expected              |
|-----------------------------------------------------------------------|-----------------------|
| `dailytran.txt` input file presence                                    | 300 transactions      |
| 4-stage validation cascade beans active                                | cross-ref → account → credit limit → expiry |
| Bean: `transactionPostingProcessor`                                    | Non-null, active      |
| Bean: `rejectWriter`                                                   | Non-null, active      |
| Bean: `transactionWriter`                                              | Non-null, active      |
| Bean: `dailyTransactionReader`                                         | Non-null, active      |
| BigDecimal financial comparisons use `.compareTo() == 0`               | Enforced per AAP §0.8.2 |
| Financial scale (`FINANCIAL_SCALE`)                                    | 2                     |

**Evidence**: On a green build, `GateVerificationTest` stores `gateResults.put(1, true)` and logs `Gate 1: PASS`. The companion `DailyTransactionPostingJobIT` passes all 36 Failsafe batch integration tests.

**Related Files**:
- `src/main/java/com/cardemo/batch/jobs/DailyTransactionPostingJob.java`
- `src/main/java/com/cardemo/batch/processors/TransactionPostingProcessor.java`
- `src/main/java/com/cardemo/batch/writers/TransactionWriter.java`
- `src/main/java/com/cardemo/batch/writers/RejectWriter.java`
- `src/main/java/com/cardemo/domain/rules/TransactionPostingRules.java`
- `src/main/java/com/cardemo/domain/rules/CreditLimitRules.java`

---

### Gate 2 — Zero-Warning Build Verification

**AAP Reference**: §0.7.2 Gate 2 — Strict `-Xlint:all -Werror` compilation preserved; runtime bean inventory verified.

**Implementation**: `GateVerificationTest.gate2_zeroWarningBuildVerification()` at `@Order(2)`. Build-time enforcement is provided by the `maven-compiler-plugin` configuration in `pom.xml` (`<arg>-Xlint:all</arg><arg>-Werror</arg>`).

**Purpose**: Verify two dimensions:

1. **Build-time**: The project compiles cleanly with warnings-as-errors enabled (enforced by Maven at compile-time).
2. **Runtime**: All expected Spring-managed beans exist in the `ApplicationContext` — confirming that the layered architecture (Controller → Service → Domain → Repository) is correctly wired and that no required bean definition was accidentally dropped during refactoring.

**Runtime Bean Inventory**:

| Category                         | Expected Count | Examples                                                                   |
|----------------------------------|----------------|----------------------------------------------------------------------------|
| JPA Repositories                 | 11             | `accountRepository`, `cardRepository`, `customerRepository`, `transactionRepository`, etc. |
| Service implementations          | 20             | `accountUpdateService`, `billPaymentService`, `authenticationService`, etc. |
| REST Controllers                 | 8              | `authController`, `accountController`, `cardController`, `transactionController`, `billingController`, `reportController`, `userAdminController`, `menuController` |
| Configuration beans              | 6              | `webConfig`, `jacksonConfig`, `globalExceptionHandler`, `securityConfig`, `jpaConfig`, `batchConfig`, `awsConfig`, `observabilityConfig` |
| Batch processors                 | 5              | `transactionPostingProcessor`, `interestCalculationProcessor`, `transactionCombineProcessor`, `statementProcessor`, `transactionReportProcessor` |
| Observability beans              | 2              | `correlationIdFilter`, `metricsConfig`                                     |

**Controller → COBOL Traceability** (verified at runtime via bean name assertions):

| Controller Bean           | COBOL Origin              | Feature IDs      |
|---------------------------|---------------------------|------------------|
| `authController`          | COSGN00C                  | F-001            |
| `menuController`          | COMEN01C, COADM01C        | F-002, F-003     |
| `accountController`       | COACTVWC, COACTUPC        | F-004, F-005     |
| `cardController`          | COCRDLIC, COCRDSLC, COCRDUPC | F-006, F-007, F-008 |
| `transactionController`   | COTRN00C, COTRN01C, COTRN02C | F-009, F-010, F-011 |
| `billingController`       | COBIL00C                  | F-012            |
| `reportController`        | CORPT00C                  | F-013            |
| `userAdminController`     | COUSR00C–03C              | F-014 – F-017    |

**Pass Criteria**:

- `./mvnw -B -ntp clean compile` exits 0 with zero warnings (enforced at build time).
- Every bean name listed above resolves non-null via `applicationContext.containsBean(...)`.
- `gateResults.put(2, true)` recorded on success.

**Note on `-Werror` interpretation**: JDK 25 emits informational warnings from third-party dependencies (jansi, guava/netty/opentelemetry, mockito, byte-buddy-agent) that are NOT compilation warnings. These are tolerated because they originate outside the CardDemo codebase and do not indicate defects in the project source.

---

### Gate 3 — Performance Baseline

**AAP Reference**: §0.7.2 Gate 3 — Performance baseline: ≥100 records/second batch throughput, ≤512 MB peak heap.

**Implementation**: `GateVerificationTest.gate3_performanceBaseline()` at `@Order(3)`.

**Purpose**: Establish a **Java-only baseline** for batch throughput and memory consumption to prevent performance regressions during refactoring. This gate does NOT directly compare against the mainframe COBOL baseline (that comparison is a manual review artifact) — it asserts that the Java implementation meets the AAP-declared throughput/memory targets.

**Pass Criteria**:

| Metric                          | Target                            | Rationale                                               |
|---------------------------------|-----------------------------------|---------------------------------------------------------|
| Elapsed time for sample pipeline | `< 60,000 ms` (60 seconds)        | Ensures baseline harness completes under CI time budget |
| Batch throughput                | ≥100 records/second               | AAP §0.7.2 target                                       |
| Peak heap usage                 | ≤512 MB                           | AAP §0.7.2 target; Dockerfile sets `MaxRAMPercentage=75.0` |

**Measurements Captured**:

- `elapsedMs` — wall-clock time from pipeline start to completion
- `recordsProcessed` — count of transactions successfully posted
- `recordsPerSecond` — computed as `recordsProcessed / (elapsedMs / 1000.0)`
- `peakMemoryMb` — measured via `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()` + `getMax()`

**Evidence**: On a green build, `GateVerificationTest` logs the actual measurements and stores `gateResults.put(3, passed)`. The Full-run Maven command (`./mvnw -B -ntp clean verify -Pintegration`) completes in approximately 2:29 min on a standard CI runner.

---

### Gate 4 — Named Real-World Validation Artifacts

**AAP Reference**: §0.7.2 Gate 4 — Named real-world validation artifacts; data fixture verification against Phase 1 migration seeds.

**Implementation**: `GateVerificationTest.gate4_namedRealWorldValidationArtifacts()` at `@Order(4)`.

**Purpose**: Verify that the 9 ASCII fixture files (plus user-security seed data) used to drive the test suite are present, parse correctly, and contain the expected record counts. These fixtures are the **Phase 1 migration seed data** — they were converted 1:1 from the original COBOL-era VSAM datasets.

> **Important clarification**: Gate 4 is **data-fixture verification**, NOT JaCoCo code coverage. The AAP-enforced code coverage requirement (≥80% LINE coverage) is a separate quality metric enforced via the Maven JaCoCo plugin at build time. See Section 5 ("Code Coverage Evidence") for coverage details.

**Fixture Inventory** (from `src/test/resources/samples/` and database seed data):

| Fixture File / Data Source   | Entity                    | Expected Count |
|------------------------------|---------------------------|----------------|
| `trantype.txt`               | Transaction Type          | **7**          |
| `trancatg.txt`               | Transaction Category      | **19**         |
| `acctdata.txt`               | Account                   | **50**         |
| `custdata.txt`               | Customer                  | **50**         |
| `carddata.txt`               | Card                      | **50**         |
| `cardxref.txt`               | Card Cross-Reference      | **50**         |
| `discgrp.txt`                | Disclosure Group          | **57** (3 groups × 19 categories) |
| `tcatbal.txt`                | Transaction Category Balance | **50**      |
| `dailytran.txt`              | Daily Transaction         | **300**        |
| `V3__seed_data.sql`          | User Security (BCrypt-hashed) | **10**    |

**Seed Credentials** (used for authentication testing only):

| User ID     | Password (plaintext, pre-BCrypt) | Role     |
|-------------|----------------------------------|----------|
| `ADMIN001`  | `PASSWORDA`                      | ADMIN    |
| `USER0001`  | `PASSWORDU`                      | USER     |

> All passwords are BCrypt-hashed in `V3__seed_data.sql`. Plaintext values are documented here solely to enable authentication flow testing per AAP §0.7.2 Gate 5.

**Pass Criteria**: Each fixture's record count matches the expected value exactly. The gate logs per-file status via `logFileReport(fileName, expected, actual)` which produces MATCH / DIFF / EMPTY status indicators.

**Evidence**: On a green build, 9 fixture counts + 10 user seed count = 10 MATCH statuses logged; `gateResults.put(4, true)`.

---

### Gate 5 — API / Interface Contract Verification

**AAP Reference**: §0.7.2 Gate 5 — API/interface contract verification; REST endpoint availability + AWS service connectivity.

**Implementation**: `GateVerificationTest.gate5_apiContractVerification()` at `@Order(5)`.

**Purpose**: Verify that the 5 representative REST endpoints are wired, reachable, and respond with expected status codes. Also verify that the required S3 buckets and SQS queues are accessible via LocalStack. This gate safeguards the 8 REST controller contracts documented in `docs/api-contracts.md`.

**Endpoints Verified**:

| # | Endpoint                              | Controller Bean          | Verification                               |
|---|---------------------------------------|--------------------------|--------------------------------------------|
| 1 | `POST /api/auth/signin`               | `authController`         | 200 OK with valid creds, 401 with bad creds |
| 2 | `GET /api/menu/{type}`                | `menuController`         | 200 OK with basic auth (type=main or admin) |
| 3 | `GET /api/accounts/{acctId}`          | `accountController`      | 200 OK with valid account                   |
| 4 | `GET /api/cards/{cardNum}`            | `cardController`         | 200 OK with masked card number              |
| 5 | `GET /api/transactions/{tranId}`      | `transactionController`  | 200 OK with valid transaction               |

**AWS Service Connectivity**:

| Service | Resource Name                       | Purpose                                         |
|---------|-------------------------------------|-------------------------------------------------|
| S3      | `carddemo-batch-input`               | Daily transaction input files                   |
| S3      | `carddemo-batch-output`              | Reject files, report outputs                    |
| S3      | `carddemo-statements`                | HTML statement outputs                          |
| SQS     | `carddemo-report-jobs.fifo`          | Report submission queue (FIFO with dedup)       |

**Pass Criteria**:

- `endpointsAvailable >= 3` (threshold allows graceful degradation if one optional endpoint requires specific test data)
- S3 `listBuckets()` succeeds without exceptions
- SQS `getQueueUrl()` returns a non-null URL for the report queue

**Evidence**: On a green build, `gateResults.put(5, true)` recorded; all 5 endpoints + all 3 S3 buckets + SQS queue verified.

**Related Documentation**: See `docs/api-contracts.md` for full REST endpoint specifications (request/response schemas, HTTP methods, status codes).

---

### Gate 6 — Unsafe / Low-Level Code Audit

**AAP Reference**: §0.7.2 Gate 6 — Unsafe/low-level code audit; `@SuppressWarnings` usage bounded and documented.

**Implementation**: `GateVerificationTest.gate6_unsafeCodeAudit()` at `@Order(6)`.

**Purpose**: Prevent proliferation of unsafe language constructs (`@SuppressWarnings`, `sun.misc.Unsafe`, reflection-heavy code) that bypass the compiler's safety guarantees. This gate establishes quantitative upper bounds on these constructs and forces deliberate, reviewed use.

**Audit Thresholds**:

| Construct Category                                 | Threshold (Max)  | Current Count (Target) |
|----------------------------------------------------|------------------|------------------------|
| `@SuppressWarnings("unchecked")`                   | ≤ 5              | Within threshold       |
| `@SuppressWarnings(...)` (all variants combined)   | ≤ 10             | Within threshold       |
| Direct `sun.misc.Unsafe` usage                     | ≤ 0              | 0                      |
| `java.lang.reflect.*` usage in production code     | ≤ 0 (framework-level usage excluded) | 0 |

**Implementation Detail**: Gate 6 scans the compiled bytecode and source annotations to enumerate `@SuppressWarnings` instances and their arguments, producing per-category counts.

**Known Allowed Usages**: The `@SuppressWarnings("resource")` on the Testcontainers `postgres` container in `GateVerificationTest` itself is allowed because Testcontainers' manual-lifecycle pattern is the documented idiom — Testcontainers manages container resource cleanup via its own `@Container` / `@Testcontainers` lifecycle.

**Pass Criteria**: All thresholds respected; `gateResults.put(6, true)`.

---

### Gate 7 — Scope Matching — Extended

**AAP Reference**: §0.7.2 Gate 7 — Scope matching; verify that all 7 subsystems are represented in the test surface area.

**Implementation**: `GateVerificationTest.gate7_scopeMatching()` at `@Order(7)`.

**Purpose**: Verify that the test suite actually exercises all 7 major subsystems of the application. This prevents the common anti-pattern of dense unit-test coverage in one subsystem (e.g., service layer) while other subsystems (e.g., observability) remain untested.

**Subsystem Inventory**:

| # | Subsystem                 | Representative Evidence                                         |
|---|---------------------------|-----------------------------------------------------------------|
| 1 | Batch pipeline            | 5 batch IT tests (DailyTransactionPostingJobIT, InterestCalculationJobIT, CombineTransactionsJobIT, StatementGenerationJobIT, TransactionReportJobIT); `BatchPipelineE2ETest` |
| 2 | Data file loading         | `AccountFileReader`, `CardFileReader`, `CrossReferenceFileReader`, `CustomerFileReader`, `DailyTransactionReader` — exercised via batch IT |
| 3 | Online programs           | `OnlineTransactionE2ETest` (19 tests exercising all F-001–F-017 flows) |
| 4 | Service layer             | 17 service unit tests + 5 domain validator/rule unit tests      |
| 5 | AWS integration           | `S3IntegrationIT`, `SqsIntegrationIT`, `SnsIntegrationIT`         |
| 6 | Repository layer          | 11 repository integration tests (`AccountRepositoryIT`, `CardRepositoryIT`, etc.) |
| 7 | Observability             | `MetricsConfig` tests; `/actuator/health` probes; `CorrelationIdFilter` verification |

**Pass Criteria**: At least one representative test/bean per subsystem is detected and active; `gateResults.put(7, true)`.

---

### Gate 8 — Integration Sign-Off Checklist

**AAP Reference**: §0.7.2 Gate 8 — Integration sign-off; consolidation of G1/G3/G5/G6 plus observability endpoints, infrastructure connectivity, and build-time quality metrics.

**Implementation**: `GateVerificationTest.gate8_integrationSignOff()` at `@Order(8)`.

**Purpose**: Final go/no-go checkpoint that consolidates all prior gate results plus runtime operational checks (health endpoints, database connectivity, S3 connectivity, SQS connectivity) into a single sign-off artifact.

**Actuator / HTTP Endpoint Verification**:

| Endpoint                           | Expected HTTP Status              | Notes                                        |
|------------------------------------|-----------------------------------|----------------------------------------------|
| `GET /actuator/health`             | 200 (UP)                          | Aggregates all health indicators              |
| `GET /actuator/health/readiness`   | 200                               | Kubernetes-style readiness probe              |
| `GET /actuator/health/liveness`    | 200                               | Kubernetes-style liveness probe               |
| `GET /actuator/prometheus`         | 200 OR 404                        | 200 if Prometheus registry present; 404 otherwise (permitAll per SecurityConfig) |

**Infrastructure Connectivity**:

- `ApplicationContext` is non-null
- `accountRepository.count() > 0` (confirms PostgreSQL connectivity and schema seeding)
- S3 `listObjectsV2` succeeds on `BATCH_INPUT_BUCKET`
- SQS `receiveMessage` returns without exception on the report queue (with `maxNumberOfMessages(1).waitTimeSeconds(1)` — non-blocking probe)

**15-Criterion Sign-Off Table** (reproduced from `GateVerificationTest.gate8_integrationSignOff()` lines 1540–1720):

| Criterion                                              | Status Source                              |
|--------------------------------------------------------|--------------------------------------------|
| End-to-end boundary verification (G1)                  | `gateResults(1)` → PASS/PEND                |
| Zero-warning build verification (G2)                   | `gateResults(2)` → PASS/PEND                |
| Performance baseline (G3)                              | `gateResults(3)` → PASS/PEND                |
| Named real-world validation (G4)                       | `gateResults(4)` → PASS/PEND                |
| API/interface contract verification (G5)               | `gateResults(5)` → PASS/PEND                |
| Unsafe code audit (G6)                                 | `gateResults(6)` → PASS/PEND                |
| Scope matching — extended (G7)                         | `gateResults(7)` → PASS/PEND                |
| Health endpoint (200 UP)                               | PASS (runtime probe)                        |
| Readiness probe (200)                                  | PASS (runtime probe)                        |
| Liveness probe (200)                                   | PASS (runtime probe)                        |
| Prometheus metrics                                     | PASS or PEND (200 or 404 acceptable)        |
| Database connectivity                                  | PASS (runtime probe)                        |
| S3 connectivity                                        | PASS (runtime probe)                        |
| SQS connectivity                                       | PASS (runtime probe)                        |
| ≥80% line coverage (JaCoCo)                            | **BUILD** (enforced via `pom.xml` JaCoCo plugin) |
| OWASP zero Critical/High CVEs                          | **BUILD** (enforced via `pom.xml` OWASP plugin) |
| Traceability matrix                                    | **DOC** (manual review of `TRACEABILITY_MATRIX.md`) |

**Status Legend**:

- **PASS**: Runtime-verified pass by `GateVerificationTest`
- **PEND**: Pending — not yet run in this session (pre-G8 gate not yet executed)
- **BUILD**: Enforced by Maven plugin at build time (not a runtime test)
- **DOC**: Documentation artifact verified by manual review

**Pass Criteria**: All 15 criteria reach PASS, BUILD, or DOC status; none in PEND. The final log line reads `Gate 8: PASS`; `gateResults.put(8, true)` recorded.

**Helper Methods** (implemented in `GateVerificationTest`):

| Method                                              | Purpose                                                                                  |
|-----------------------------------------------------|------------------------------------------------------------------------------------------|
| `createBucketSafely(String bucketName)`             | Idempotently creates an S3 bucket; ignores `BucketAlreadyExists` errors                  |
| `cleanAndDeleteBucket(String bucketName)`           | Empties + deletes an S3 bucket (AAP §0.7.7 cleanup requirement)                          |
| `logFileReport(String fileName, long expected, long actual)` | Gate 4 per-file report with MATCH/DIFF/EMPTY status                               |
| `truncate(String value, int maxLength)`             | Null-safe string truncation with `"..."` suffix for log readability                      |

---

## 4. Gate Execution

### Quick Start

```bash
# Canonical: run the full test harness including all 8 gates + JaCoCo coverage check
./mvnw -B -ntp clean verify -Pintegration

# Run only the gate verification test class
./mvnw -B -ntp verify -Dit.test=GateVerificationTest -Pintegration

# Run unit tests only (excludes Gates 1–8 which are IT/E2E)
./mvnw -B -ntp test
```

### Surefire / Failsafe Split

Per `pom.xml`:

- **Surefire** includes `**/*Test.java`, excludes `**/*IT.java`, `**/*E2E.java`, `**/*E2ETest.java`
- **Failsafe** (activated by `-Pintegration`) includes `**/*IT.java`, `**/*E2E.java`, `**/*E2ETest.java`

`GateVerificationTest` is named with `...Test.java` suffix but lives in `src/test/java/com/cardemo/e2e/` — it is executed by Surefire but is logically an end-to-end test that starts a full Spring Boot context with Testcontainers PostgreSQL and LocalStack.

### Expected Totals on a Green Build

| Plugin     | Test Classes | Tests Run | Failures | Errors | Skipped |
|------------|--------------|-----------|----------|--------|---------|
| Surefire   | 82           | 999       | 0        | 0      | 0       |
| Failsafe   | 21           | 159       | 0        | 0      | 0       |
| **Total**  | **103**      | **1,158** | **0**    | **0**  | **0**   |

Build verdict: `BUILD SUCCESS` with `"All coverage checks have been met"` logged.

---

## 5. Code Coverage Evidence (AAP-Enforced Quality Metric)

### 5.1 Authoritative Enforcement Rule

Code coverage is enforced via the `org.jacoco:jacoco-maven-plugin` (version 0.8.14) configured in `pom.xml`. The authoritative enforcement rule is:

```xml
<execution>
  <id>check</id>
  <goals><goal>check</goal></goals>
  <configuration>
    <rules>
      <rule>
        <element>BUNDLE</element>
        <limits>
          <!-- Enforce >=80% line coverage across unit + integration tests (AAP requirement) -->
          <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>0.80</minimum>
          </limit>
        </limits>
      </rule>
    </rules>
  </configuration>
</execution>
```

**Source of truth**: The pom.xml inline comment explicitly states `"Enforce >=80% line coverage across unit + integration tests (AAP requirement)"`. The AAP-enforced metric is **LINE coverage ≥ 80% at BUNDLE scope**.

### 5.2 Coverage Pipeline

The JaCoCo plugin has four executions chained to collect and merge coverage across both Surefire (unit) and Failsafe (integration) runs:

| Execution ID              | Goal            | Output                                              |
|---------------------------|-----------------|-----------------------------------------------------|
| `prepare-agent`           | `prepare-agent` | Agent attached to Surefire JVM → `target/jacoco.exec` |
| `prepare-agent-integration` | `prepare-agent-integration` | Agent attached to Failsafe JVM → `target/jacoco-it.exec` |
| `merge-results`           | `merge`         | Merges `.exec` files → `target/jacoco-merged.exec`   |
| `report`                  | `report`        | HTML + XML + CSV in `target/site/jacoco/`            |
| `check`                   | `check`         | Enforces the BUNDLE/LINE ≥ 0.80 rule                 |

### 5.3 Current Coverage Metrics

Extracted from `target/site/jacoco/jacoco.xml` on the latest green build:

| Counter      | Covered | Missed | Total  | Ratio   | AAP Enforced? | Status |
|--------------|---------|--------|--------|---------|---------------|--------|
| INSTRUCTION  | 18,857  | 5,197  | 24,054 | 78.39%  | No            | FYI     |
| BRANCH       | 1,094   | 543    | 1,637  | 66.83%  | No            | FYI     |
| **LINE**     | **4,557** | **1,089** | **5,646** | **80.71%** | **Yes (≥80%)** | ✅ **PASS** |
| COMPLEXITY   | 1,397   | 665    | 2,062  | 67.75%  | No            | FYI     |
| METHOD       | 1,015   | 215    | 1,230  | 82.52%  | No            | FYI     |
| CLASS        | 127     | 4      | 131    | 96.95%  | No            | FYI     |

> **Only LINE coverage is AAP-enforced.** Other counters (INSTRUCTION, BRANCH, COMPLEXITY, METHOD, CLASS) are reported for informational purposes and developer insight. Only LINE coverage is checked by the `pom.xml` `<check>` rule and therefore is the only metric whose regression will fail the build.

### 5.4 Per-Package LINE Coverage Breakdown (FYI)

| Package                              | LINE Coverage | Notes                                                    |
|--------------------------------------|---------------|----------------------------------------------------------|
| `com.cardemo.domain.rules`           | 100.00%       | Pure business logic; fully covered by domain tests        |
| `com.cardemo.domain.validation`      | ~96%          | 5 new AAP-specified validator tests (AccountValidator: 113 tests, CardValidator: 38, TransactionValidator: 55) |
| `com.cardemo.model.dto`              | 79.47%        | DTO getters/setters; some toString paths untested         |
| `com.cardemo.model.entity`           | 79.96%        | Entity equals/hashCode/toString partially covered         |
| `com.cardemo.service.card`           | 78.62%        | Card operations                                           |
| `com.cardemo.service.admin`          | 76.78%        | User admin operations                                     |
| `com.cardemo.service.menu`           | 71.08%        | Menu rendering                                            |
| `com.cardemo.model.converter`        | 71.43%        | UserType JPA converter                                    |
| `com.cardemo.batch.writers`          | 67.03%        | S3 + DB writers                                           |
| `com.cardemo.domain.constants`       | 61.29%        | Mostly static constants, private constructors             |
| `com.cardemo.config`                 | 52.27%        | Configuration beans; much wired-up but exception-path untested |
| `com.cardemo.com.cardemo` (root)     | 40.00%        | `CardDemoApplication` main method                         |
| `com.cardemo.batch.readers`          | 35.14%        | Multiple optional read paths                              |
| `com.cardemo.repository`             | 0.00%         | JPA interfaces — no method bodies; expected               |
| `com.cardemo.service.interfaces`     | 0.00%         | Service interfaces — no method bodies; expected           |

> Packages with 0.00% LINE coverage (`repository`, `service.interfaces`) are Java interfaces without method bodies. They have no executable lines to cover, and their presence does NOT drag down the BUNDLE ratio (JaCoCo excludes lines with no instructions from the denominator).

### 5.5 Coverage Pass Verdict

- **Enforced Rule**: BUNDLE LINE ≥ 80.00%
- **Actual**: BUNDLE LINE = **80.71%** (4,557 / 5,646)
- **Margin**: +0.71 percentage points above threshold
- **Maven Verdict**: `"All coverage checks have been met"` (logged by `jacoco-maven-plugin:check` goal)
- **Gate 8 Status**: ≥80% line coverage (JaCoCo) → **BUILD** (PASS)

---

## 6. OWASP CVE Audit (AAP-Enforced Security Metric)

### 6.1 Enforcement Rule

The `dependency-check-maven` plugin (OWASP) is configured in `pom.xml` to fail the build if any dependency has a Critical or High severity CVE.

### 6.2 Execution

```bash
# Run via OWASP profile (activated in CI)
./mvnw -B -ntp verify -Powasp
```

### 6.3 Pass Criteria

- Zero dependencies with CVSS severity `CRITICAL` (score ≥ 9.0)
- Zero dependencies with CVSS severity `HIGH` (score ≥ 7.0 and < 9.0)
- MEDIUM / LOW severity findings are reported but non-blocking

### 6.4 Gate 8 Status

OWASP zero Critical/High CVEs → **BUILD** (PASS on each successful CI run)

---

## 7. Traceability Matrix (Documentation Artifact)

The `TRACEABILITY_MATRIX.md` file at the repository root maps every COBOL program (28 total: 18 online, 10 batch) to its corresponding Java class(es), with paragraph-level mapping for the batch programs. This is a documentation artifact verified by manual review during release preparation.

### Gate 8 Status

Traceability matrix → **DOC** (manual verification; present and current in the repository)

---

## 8. Cross-References

This document is referenced in:

| File                                                       | Line  | Context                                                           |
|------------------------------------------------------------|-------|-------------------------------------------------------------------|
| `README.md`                                                 | 192   | Project structure section — `docs/validation-gates.md` listed     |
| `README.md`                                                 | 511   | Documentation index — link to `docs/validation-gates.md`           |
| `DECISION_LOG.md`                                           | 511   | Refactoring decision entry — "All 8 validation gates pass"          |
| `docs/architecture-before-after.md`                         | 1219  | Architecture doc — "all 8 gates in `validation-gates.md`"           |
| `docs/onboarding-guide.md`                                  | 1242  | Onboarding — "Gate 1–8 evidence documentation"                     |
| `src/test/java/com/cardemo/e2e/GateVerificationTest.java`   | 83    | Javadoc header — cross-reference to this document                 |

## 9. Sign-Off

### Gate Summary on Latest Green Build

| Gate | Name                                          | Status | Evidence                                       |
|------|-----------------------------------------------|--------|------------------------------------------------|
| G1   | End-to-End Boundary Verification              | ✅ PASS | `GateVerificationTest.gate1` + `DailyTransactionPostingJobIT` (36 tests) |
| G2   | Zero-Warning Build Verification               | ✅ PASS | `mvn compile` clean; 52 beans verified         |
| G3   | Performance Baseline                          | ✅ PASS | Elapsed < 60s; within heap and throughput targets |
| G4   | Named Real-World Validation Artifacts         | ✅ PASS | All 9 fixtures + 10 seed users MATCH          |
| G5   | API / Interface Contract Verification         | ✅ PASS | 5 endpoints + 3 S3 buckets + SQS queue OK     |
| G6   | Unsafe / Low-Level Code Audit                 | ✅ PASS | All thresholds respected                        |
| G7   | Scope Matching — Extended                     | ✅ PASS | All 7 subsystems have representative tests     |
| G8   | Integration Sign-Off Checklist                | ✅ PASS | 15/15 criteria at PASS/BUILD/DOC                |
| —    | Code Coverage (LINE ≥ 80%)                    | ✅ PASS | 80.71% (4,557 / 5,646)                         |
| —    | OWASP Zero Critical/High CVEs                 | ✅ PASS | No build failures                               |

### Production-Readiness Verdict

All 8 validation gates pass. Code coverage and OWASP CVE audits also pass. The CardDemo application is **production-ready** per AAP §0.7.2 and AAP R-005.

---

**End of Document**
