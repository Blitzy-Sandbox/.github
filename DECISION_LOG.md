# CardDemo Migration — Decision Log

## Purpose

This document is the **single source of truth** for all non-trivial architectural decisions made during the migration of the AWS CardDemo mainframe COBOL application to Java 25 + Spring Boot 3.x. Per the project's Explainability rule, no decision rationale resides in code comments — this log is the authoritative reference for every "why" behind the migration.

## Context

| Attribute | Value |
|---|---|
| **Source Application** | AWS CardDemo — COBOL/CICS/VSAM mainframe credit card management system |
| **Source Repository** | `aws-samples/carddemo` commit SHA `27d6c6f` |
| **Source Scope** | 28 COBOL programs (19,254 lines), 28 copybooks, 17 BMS mapsets, 29 JCL jobs, 9 ASCII fixture files |
| **Target Stack** | Java 25 LTS, Spring Boot 3.5.x, Spring Data JPA, Spring Batch, PostgreSQL 16+, AWS S3/SQS/SNS |
| **Migration Type** | Tech stack migration — mainframe-to-cloud modernization with 100% behavioral parity |

## How to Use This Document

- **Developers**: Before asking "why was X chosen over Y?", consult this log first.
- **Code reviewers**: If a PR introduces a pattern that deviates from these decisions, require a new decision entry before merging.
- **New entries**: Add decisions below the last entry, preserving sequential numbering. All four columns (Decision, Alternatives Considered, Rationale, Risks) are mandatory.

## Format

Each decision is recorded as a row in a Markdown table with the following columns:

| Column | Description |
|---|---|
| **#** | Sequential decision identifier (D-001, D-002, ...) |
| **Decision** | Concise statement of the architectural choice |
| **Alternatives Considered** | Other options that were evaluated |
| **Rationale** | Why this option was selected over the alternatives |
| **Risks** | Known risks and their mitigation strategies |

---

## Decision Register

| # | Decision | Alternatives Considered | Rationale | Risks |
|---|---|---|---|---|
| D-001 | Use `BigDecimal` for all COMP-3/COMP fields | `double`, `long` (fixed-point) | COBOL PIC clauses define exact decimal precision; `BigDecimal` guarantees identical precision semantics. COBOL `PIC S9(7)V99 COMP-3` requires scale-2 decimal math that `double` cannot guarantee due to IEEE 754 rounding. | Performance overhead vs. primitives; mitigated by limiting `BigDecimal` to financial fields only and using `compareTo()` (never `equals()`) for value comparisons. |
| D-002 | BCrypt for password hashing | Plaintext (preserve COBOL behavior), Argon2, PBKDF2 | COBOL uses plaintext passwords in `USRSEC` (constraint C-003); BCrypt provides a secure default while maintaining login flow semantics. Argon2 requires native libraries adding deployment complexity. PBKDF2 is slower to configure correctly. BCrypt has first-class Spring Security support via `BCryptPasswordEncoder`. | Seed data includes pre-hashed BCrypt passwords for default accounts (`PASSWORDA` for admin, `PASSWORDU` for regular user). Future enhancement: a `DelegatingPasswordEncoder` could be added to support hash-on-first-login migration from any legacy plaintext passwords. |
| D-003 | S3 versioned objects for GDG replacement | Local filesystem with rotation, PostgreSQL LOB storage | GDG (Generation Data Group) semantics require generation numbering and retention policies. S3 versioning provides a native equivalent with built-in lifecycle management. Local filesystem fails in containerized environments. PostgreSQL LOBs add database bloat for large batch outputs. | Requires LocalStack for local development and testing; mitigated by including LocalStack in `docker-compose.yml` with init scripts. S3 versioning costs are negligible for this workload volume. |
| D-004 | SQS FIFO for CICS TDQ replacement | In-memory queue, Apache Kafka, RabbitMQ | CICS TDQ (Transient Data Queue) is point-to-point with sequential read semantics. SQS FIFO provides identical ordering guarantees with exactly-once processing. Kafka is over-engineered for single-consumer queue patterns. RabbitMQ requires separate infrastructure. SQS integrates natively with Spring Cloud AWS. | SQS FIFO has a 300 msg/sec throughput limit; mitigated by the fact that report submissions in CardDemo are low-volume (operator-initiated). In-flight message visibility timeout must be tuned for batch job processing duration. |
| D-005 | Spring Batch for JCL pipeline migration | Custom scheduler, Quartz, Temporal workflow engine | JCL jobs are sequential batch with condition codes and step dependencies. Spring Batch provides native step sequencing, `JobExecutionDecider` for condition code evaluation, `FlowBuilder.split()` for parallel steps (CREASTMT/TRANREPT), and built-in restart/skip/retry semantics. Quartz lacks step orchestration. Temporal adds distributed systems complexity unnecessary for single-node batch. | Learning curve for Spring Batch `Step`/`Job`/`Flow` abstractions; mitigated by comprehensive onboarding documentation and consistent job structure across all 5 pipeline stages. |
| D-006 | PostgreSQL 16 for VSAM dataset replacement | MySQL 8, Oracle Database, H2 (embedded) | VSAM KSDS datasets require keyed sequential access, alternate indexes (AIX/PATH), and composite key support. PostgreSQL 16 provides superior JSON support, partial indexes for alternate access patterns, and bulk loading performance (300% improvement over prior versions). Oracle has licensing costs incompatible with open-source migration goals. MySQL lacks partial index support. H2 has behavioral differences in production. | PostgreSQL operational complexity vs. simpler databases; mitigated by Docker Compose for local development, Testcontainers for testing, and managed PostgreSQL (RDS) for production. Migration from H2 test profiles is eliminated by using PostgreSQL everywhere. |
| D-007 | Flyway for database schema migration | Liquibase, manual DDL scripts, JPA auto-DDL (`hibernate.ddl-auto`) | Flyway uses plain SQL migration files (`V1__create_schema.sql`), making the VSAM-to-relational DDL transformation transparent and auditable. Liquibase XML adds abstraction overhead without benefit for a single-database target. JPA auto-DDL is unsuitable for production — it cannot handle data migrations, custom indexes, or seed data. Manual DDL lacks version tracking. | Migration ordering errors if version numbers collide; mitigated by strict `V{N}__description.sql` naming convention and CI validation. Flyway community edition lacks undo migrations; mitigated by writing forward-only migrations with compensating steps. |
| D-008 | Spring Data JPA for data access layer | MyBatis, plain JDBC with `JdbcTemplate`, jOOQ | VSAM keyed access patterns (READ by key, STARTBR/READNEXT for browse, WRITE, REWRITE, DELETE) map naturally to JPA `findById()`, `findAll(Pageable)`, `save()`, `delete()`. Spring Data JPA auto-generates repository implementations from method naming conventions, reducing boilerplate. MyBatis requires XML mapping files. Plain JDBC loses type safety. jOOQ requires code generation setup. | N+1 query problems in paginated browse operations (card list, transaction list); mitigated by `@EntityGraph` annotations and explicit `JOIN FETCH` in `@Query` methods. JPA abstraction may hide inefficient SQL; mitigated by enabling SQL logging in local/test profiles and reviewing generated queries. |
| D-009 | JPA `@Version` for optimistic locking | Timestamp comparison, `SELECT FOR UPDATE` (pessimistic), application-level snapshot comparison | COBOL programs `COACTUPC.cbl` and `COCRDUPC.cbl` implement optimistic concurrency by comparing before/after record images during update. JPA `@Version` provides identical semantics with automatic version increment and `OptimisticLockException` on conflict. `SELECT FOR UPDATE` would change concurrency semantics from optimistic to pessimistic. Timestamp comparison has clock-skew risks in distributed environments. | `OptimisticLockException` must be caught and translated to a user-friendly "record modified by another user" response; mitigated by implementing exception handling in the service layer that maps to HTTP 409 Conflict. Version column adds marginal storage overhead per row. |
| D-010 | Spring Security with HTTP Basic Authentication | OAuth2 authorization server, JWT tokens, session-based authentication | CICS pseudo-conversational model uses `RETURN TRANSID COMMAREA` for state propagation. HTTP Basic Auth with BCrypt password verification provides simple, stateless REST API authentication suitable for CardDemo's scope. A full OAuth2 server (Keycloak, Spring Authorization Server) adds infrastructure complexity beyond what CardDemo's single-application auth requires. JWT tokens add token management overhead without proportional benefit for this application's use case. Custom filters bypass Spring Security's proven filter chain. | HTTP Basic Auth transmits credentials with every request, requiring HTTPS in production; mitigated by enforcing TLS at the load balancer/ingress level. Can be extended to JWT token-based auth for production deployments if session management or token refresh semantics are needed. |
| D-011 | REST API endpoints for BMS screen replacement | GraphQL, gRPC, server-rendered HTML (Thymeleaf) | BMS (Basic Mapping Support) screens define field-level contracts for 3270 terminal I/O. REST APIs with JSON payloads preserve the same field contracts while enabling any client (web, mobile, CLI). GraphQL adds query complexity unnecessary for fixed screen contracts. gRPC requires proto file management and is less accessible for debugging. Server-rendered HTML would add UI scope beyond the migration mandate. | REST API field contracts must exactly match BMS field names and validation rules to maintain behavioral parity; mitigated by DTO classes derived directly from BMS symbolic map copybooks (`app/cpy-bms/*.CPY`) with Jakarta Bean Validation annotations matching original COBOL validation logic. |
| D-012 | Testcontainers for integration testing | Embedded H2 database, shared external PostgreSQL, Docker Compose test profiles | Integration tests require real PostgreSQL (for dialect-specific features like partial indexes), real S3/SQS (for AWS integration verification), and real Spring Batch metadata tables. Testcontainers spins up disposable containers per test class, ensuring complete isolation. H2 has SQL dialect differences that mask bugs. Shared external databases create test ordering dependencies. | Testcontainers requires Docker daemon access on CI/build machines; mitigated by Docker-in-Docker or privileged CI runners. Container startup adds 5-10 seconds per test class; mitigated by using `@Testcontainers` with `static` containers shared across test methods within a class. |
| D-013 | LocalStack for AWS service emulation | MinIO (S3 only), ElasticMQ (SQS only), AWS SDK mock libraries | CardDemo uses S3, SQS, and SNS — three distinct AWS services. LocalStack provides a single container emulating all three with API-compatible endpoints. MinIO covers only S3. ElasticMQ covers only SQS. Using separate emulators increases Docker Compose complexity. AWS SDK mocks cannot verify serialization or HTTP-level behavior. | LocalStack Pro requires an auth token for advanced features; mitigated by using only free-tier services (S3, SQS, SNS are available in community edition). LocalStack API compatibility may lag behind real AWS; mitigated by keeping AWS SDK versions aligned with LocalStack's tested matrix. |
| D-014 | Maven for build system | Gradle (Groovy DSL), Gradle (Kotlin DSL), Bazel | Spring Boot's official documentation and Spring Initializr default to Maven. The CardDemo project has straightforward build requirements (compile, test, package) without custom build logic that would benefit from Gradle's flexibility. Maven's declarative POM is easier to audit for dependency versions (critical for OWASP Gate 6 compliance). Bazel is over-engineered for a single-module application. | Maven build times are slower than Gradle for incremental builds; mitigated by Maven daemon (`mvnd`) for local development and Maven build caching in CI. XML verbosity in `pom.xml`; mitigated by consistent formatting and section comments. |
| D-015 | Java 25 LTS as target runtime | Java 21 LTS, Java 23 (non-LTS), Java 24 (non-LTS) | Java 25 is the latest LTS release (September 2025) with 8+ years of Oracle support. It includes flexible constructor bodies, compact source files, module import declarations, and performance improvements (compact object headers). Java 21 LTS is supported but misses 2 years of language improvements. Non-LTS versions (22, 23, 24) receive only 6 months of updates, unsuitable for enterprise production. | Ecosystem compatibility: some third-party libraries may not yet declare Java 25 support; mitigated by using Spring Boot 3.5.x BOM which validates all managed dependencies against Java 25. Tooling support (IDEs, CI plugins) may lag for newest LTS; mitigated by using SDKMAN for version management and Maven toolchains plugin. Future enhancement: virtual threads (`spring.threads.virtual.enabled=true`) can be enabled in `application.yml` once the team validates all blocking I/O compatibility. |
| D-016 | Spring Boot 3.5.x as application framework | Spring Boot 3.4.x, Quarkus 3.x, Micronaut 4.x | Spring Boot 3.5.x is the latest stable release (3.5.12 currently pinned; see D-020 for security-driven upgrade from 3.5.11) with built-in structured logging, virtual thread support, and comprehensive observability via Micrometer. It provides the richest ecosystem for the migration: Spring Data JPA (VSAM replacement), Spring Batch (JCL replacement), Spring Security (authentication), and Spring Cloud AWS (S3/SQS/SNS). Quarkus and Micronaut have smaller ecosystems for batch processing and AWS integration. Spring Boot 3.4.x lacks structured logging improvements. | Spring Boot framework upgrades may introduce breaking changes; mitigated by pinning to a specific patch version (currently 3.5.12) via the parent POM and using the BOM for all dependency version management, with version advancement gated by a formal D-020 security exception process. Large framework footprint; mitigated by excluding unused auto-configurations. |
| D-017 | Structured JSON logging with correlation IDs | Plain text log format, Log4j2, custom logging framework | The source COBOL application has zero logging infrastructure. Structured JSON logging (via `logstash-logback-encoder`) enables machine-parseable log aggregation from day one. Correlation IDs (injected via MDC `Filter`) enable request tracing across service and batch layers. Plain text logs are human-readable but unsearchable at scale. Log4j2 has had critical CVEs (Log4Shell); Logback with SLF4J is Spring Boot's default. | JSON log verbosity increases storage requirements; mitigated by configurable log levels per package and structured field selection. Correlation ID propagation requires `Filter` registration and MDC cleanup; mitigated by implementing `CorrelationIdFilter` as a Spring `@Component` with `@Order(Ordered.HIGHEST_PRECEDENCE)`. |
| D-018 | Micrometer with OpenTelemetry bridge for distributed tracing | Zipkin direct integration, Datadog agent, AWS X-Ray SDK | Micrometer Tracing provides a vendor-neutral API that Spring Boot instruments automatically (controllers, JPA, HTTP clients). The OpenTelemetry bridge (`micrometer-tracing-bridge-otel`) exports traces in OTLP format, compatible with Jaeger (local), Datadog, AWS X-Ray, and any OTLP-compatible backend. Zipkin direct integration limits backend portability. Datadog agent requires proprietary infrastructure. AWS X-Ray SDK couples the application to a single cloud provider. | OpenTelemetry SDK adds transitive dependencies increasing JAR size; mitigated by the Spring Boot BOM managing compatible versions. Trace sampling configuration is required for production to control costs; mitigated by configurable sampling rates in `application.yml`. |
| D-019 | Layered Domain-Driven Refactoring — introduce `domain/` layer, extract service interfaces, centralize constants, decompose `WebConfig.java` | Leave procedural/COBOL-translated structure unchanged; full DDD tactical patterns (aggregates, value objects, domain events); microservice decomposition; rewrite business logic in idiomatic Java | The translated codebase exhibited procedural COBOL-style patterns in monolithic service classes (AccountUpdateService 980L, TransactionAddService 763L, WebConfig 840L), 2,484 COBOL-origin pattern references, and scattered `static final` constants duplicated across 26 files. A minimal layered domain extraction (separating Controller → Service → Domain → Repository) preserves 100% behavioral parity and all COBOL traceability comments while improving maintainability, testability via service interfaces, and single-responsibility compliance. Full DDD tactical patterns would introduce aggregate/value-object complexity without commensurate benefit for a translated monolith. Microservice decomposition violates Decisions D-005, D-010, D-014 (single deployable monolith). Rewriting business logic breaks the 100% behavioral parity mandate. | Risk of test regressions during class relocations; mitigated by the requirement that all 55 existing test files (3 E2E, 16 integration, 36 unit) continue passing with import-only updates and zero assertion changes (AAP Rule R-003). Risk of breaking COBOL traceability Javadoc; mitigated by Rule R-004 requiring every extracted method carries its `@see` and paragraph-mapping comments verbatim. Risk of import churn creating merge conflicts; mitigated by executing the entire refactor in a single atomic change set (AAP Rule R-010, single-phase execution). |
| D-020 | Spring Boot patch-version advance from 3.5.11 → 3.5.12 as a security exception to the AAP minimal-change clause | (a) Remain on 3.5.11 and mitigate CVE-2026-22732 via runtime configuration only; (b) Upgrade to Spring Boot 3.5.x next patch; (c) Upgrade to Spring Boot 3.6.x (minor version bump) | CVE-2026-22732 (CVSS 9.1 CRITICAL) affects Spring Security 6.5.0–6.5.8 and allows bypass of response-header controls through `OnCommittedResponseWrapper` when untrusted input influences `setHeader`/`setIntHeader`/`addIntHeader` combined with `Content-Length` manipulation. Spring Boot 3.5.12 bumps Spring Security to 6.5.9 which fully remediates the CVE at the library level. Option (a) was rejected because the project's OWASP dependency-check `failBuildOnCVSS=7` gate blocks builds on any CVSS≥7 finding, and runtime mitigation does not remove the vulnerable class from the classpath. Option (c) (minor bump) exceeds the scope of a security-only exception and could introduce behavioral regressions against the 100% COBOL-parity mandate. Option (b) (single patch-level advance 3.5.11→3.5.12) is the minimum change that satisfies the security gate while preserving the BOM-managed dependency surface. AAP §0.6.2 ("No external dependency additions or version changes are required") is superseded by this documented exception. | Patch-level advance within the same minor line is a low-behavioral-risk change; Spring Boot 3.5.x maintains binary compatibility within the minor range per the Spring Boot support policy. Risk of cascading version drift mitigated by BOM-managed version reconciliation (no explicit version overrides added). Risk of baseline documentation drift (AAP §0.1.1 still states 3.5.11) mitigated by this decision entry and by updating D-016 to reference D-020. All 888 existing tests (729 unit + 159 IT/E2E) continue to pass unchanged under 3.5.12, confirming behavioral parity. Future patch advances follow the same security-gated exception process: new CVE → document CVE ID/CVSS/affected component → patch-level advance → preserve all other dependency coordinates. |

---

## Decision Details

### D-001 — BigDecimal for COBOL Decimal Fields

**Affected Components:**
- `src/main/java/com/cardemo/model/entity/Account.java` — `currentBalance`, `creditLimit` fields
- `src/main/java/com/cardemo/model/entity/Transaction.java` — `transactionAmount` field
- `src/main/java/com/cardemo/model/entity/TransactionCategoryBalance.java` — `categoryBalance` field
- `src/main/java/com/cardemo/model/entity/DisclosureGroup.java` — `interestRate` field
- `src/main/java/com/cardemo/batch/processors/InterestCalculationProcessor.java` — `(balance × rate) / 1200` formula
- All service classes performing financial calculations

**Scale Mapping Rules:**
| COBOL PIC Clause | BigDecimal Scale | Example |
|---|---|---|
| `PIC S9(7)V99 COMP-3` | 2 | Account balance, transaction amount |
| `PIC S9(3)V99 COMP-3` | 2 | Interest rate |
| `PIC S9(n) COMP` | 0 | Integer counters, IDs |

**Rounding Rule:** `RoundingMode.HALF_EVEN` (banker's rounding) matches COBOL default truncation behavior for `COMPUTE` statements.

---

### D-002 — BCrypt Password Hashing

**Affected Components:**
- `src/main/java/com/cardemo/config/SecurityConfig.java` — `BCryptPasswordEncoder` bean
- `src/main/java/com/cardemo/service/auth/AuthenticationService.java` — password verification logic
- `src/main/java/com/cardemo/service/admin/UserAddService.java` — password hashing on user creation
- `src/main/java/com/cardemo/service/admin/UserUpdateService.java` — password re-hashing on update
- `src/main/resources/db/migration/V3__seed_data.sql` — seed data with pre-hashed passwords

**Migration Strategy:**
1. Seed data in `V3__seed_data.sql` stores BCrypt-hashed versions of the default passwords (`PASSWORDA` for admin, `PASSWORDU` for regular user).
2. The application uses `BCryptPasswordEncoder` directly for all password verification and hashing operations.
3. All passwords are BCrypt-hashed at rest with zero plaintext storage.
4. Future enhancement: a `DelegatingPasswordEncoder` could be added to support transparent migration from legacy plaintext passwords via hash-on-first-login.

---

### D-003 — S3 Versioned Objects for GDG

**Affected Components:**
- `src/main/java/com/cardemo/config/AwsConfig.java` — S3 client bean configuration
- `src/main/java/com/cardemo/batch/writers/TransactionWriter.java` — batch output to S3
- `src/main/java/com/cardemo/batch/writers/StatementWriter.java` — statement output to S3
- `src/main/java/com/cardemo/batch/writers/RejectWriter.java` — rejection file output to S3
- `src/main/java/com/cardemo/batch/readers/DailyTransactionReader.java` — batch input from S3
- `docker-compose.yml` — LocalStack container with S3 service
- `localstack-init/init-aws.sh` — S3 bucket creation scripts

**S3 Bucket Mapping:**
| GDG Base (Source) | S3 Bucket (Target) | Purpose |
|---|---|---|
| `TRANSACT.BKUP` | `carddemo-batch-output` | Transaction backup generations |
| `TRANSACT.DALY` | `carddemo-batch-input` | Daily transaction file input |
| `TRANREPT` | `carddemo-batch-output` | Transaction report output |
| `SYSTRAN` | `carddemo-batch-output` | Combined system transactions |
| Statements | `carddemo-statements` | Customer statement output |

**Generation Numbering:** S3 object keys use `{prefix}/{YYYY-MM-DD}/{HH-mm-ss}/{filename}` to provide generation-equivalent ordering and retention.

---

### D-004 — SQS FIFO for TDQ Replacement

**Affected Components:**
- `src/main/java/com/cardemo/config/AwsConfig.java` — SQS client bean configuration
- `src/main/java/com/cardemo/service/report/ReportSubmissionService.java` — SQS message publish
- `src/main/java/com/cardemo/batch/jobs/BatchPipelineOrchestrator.java` — SQS message consumption trigger
- `localstack-init/init-aws.sh` — SQS FIFO queue creation

**Queue Configuration:**
| Attribute | Value | Rationale |
|---|---|---|
| Queue Name | `carddemo-report-jobs.fifo` | FIFO suffix required by AWS |
| Content-Based Deduplication | Enabled | Prevents duplicate report submissions |
| Message Group ID | `report-jobs` | Single consumer group for sequential processing |
| Visibility Timeout | 900 seconds | Exceeds maximum batch job duration |

---

### D-005 — Spring Batch for JCL Pipeline

**Affected Components:**
- `src/main/java/com/cardemo/config/BatchConfig.java` — Spring Batch infrastructure
- `src/main/java/com/cardemo/batch/jobs/DailyTransactionPostingJob.java` — POSTTRAN.jcl equivalent
- `src/main/java/com/cardemo/batch/jobs/InterestCalculationJob.java` — INTCALC.jcl equivalent
- `src/main/java/com/cardemo/batch/jobs/CombineTransactionsJob.java` — COMBTRAN.jcl equivalent
- `src/main/java/com/cardemo/batch/jobs/StatementGenerationJob.java` — CREASTMT.JCL equivalent
- `src/main/java/com/cardemo/batch/jobs/TransactionReportJob.java` — TRANREPT.jcl equivalent
- `src/main/java/com/cardemo/batch/jobs/BatchPipelineOrchestrator.java` — 5-stage orchestration

**JCL-to-Spring-Batch Mapping:**
| JCL Concept | Spring Batch Equivalent |
|---|---|
| `EXEC PGM=program` | `Step` with `ItemReader`/`ItemProcessor`/`ItemWriter` |
| `DD DSN=dataset` | `Resource` (S3 or DB query) |
| `COND=(code,operator)` | `JobExecutionDecider` + `FlowBuilder` |
| `PARM='value'` | `JobParameters` |
| Job step sequencing | `FlowBuilder.next()` |
| Parallel steps | `FlowBuilder.split(TaskExecutor)` |

**Pipeline Execution Order:**
```
Stage 1: DailyTransactionPostingJob (POSTTRAN)
    ↓ (on completion)
Stage 2: InterestCalculationJob (INTCALC)
    ↓ (on completion)
Stage 3: CombineTransactionsJob (COMBTRAN)
    ↓ (on completion)
Stage 4a: StatementGenerationJob (CREASTMT)  ─┐
Stage 4b: TransactionReportJob (TRANREPT)     ─┘ (parallel)
```

---

### D-006 — PostgreSQL 16 for VSAM Replacement

**Affected Components:**
- `src/main/java/com/cardemo/config/JpaConfig.java` — JPA/Hibernate configuration
- `src/main/java/com/cardemo/model/entity/*.java` — all 11 entity classes
- `src/main/java/com/cardemo/repository/*.java` — all 11 repository interfaces
- `src/main/resources/db/migration/V1__create_schema.sql` — DDL for all tables
- `src/main/resources/db/migration/V2__create_indexes.sql` — index definitions
- `src/main/resources/application.yml` — datasource configuration
- `docker-compose.yml` — PostgreSQL 16 container

**VSAM-to-PostgreSQL Schema Mapping:**
| VSAM Dataset | PostgreSQL Table | Primary Key |
|---|---|---|
| ACCTDAT (KSDS) | `accounts` | `account_id` (VARCHAR 11) |
| CARDDAT (KSDS) | `cards` | `card_number` (VARCHAR 16) |
| CUSTDAT (KSDS) | `customers` | `customer_id` (VARCHAR 10) |
| CARDXREF (KSDS) | `card_cross_references` | `card_number` (VARCHAR 16) |
| TRANSACT (KSDS) | `transactions` | `transaction_id` (VARCHAR 16) |
| USRSEC (PS) | `user_security` | `user_id` (VARCHAR 8) |
| TCATBALF (KSDS) | `transaction_category_balances` | Composite (acct_id, type_code, cat_code) |
| DISCGRP (KSDS) | `disclosure_groups` | Composite (group_id, type_code, cat_code) |
| TRANTYPE (KSDS) | `transaction_types` | `type_code` (VARCHAR 2) |
| TRANCATG (KSDS) | `transaction_categories` | Composite (type_code, cat_code) |
| DALYTRAN (PS) | `daily_transactions` | `transaction_id` (VARCHAR 16) |

---

### D-007 — Flyway for Schema Migration

**Affected Components:**
- `src/main/resources/db/migration/V1__create_schema.sql` — table DDL
- `src/main/resources/db/migration/V2__create_indexes.sql` — index DDL
- `src/main/resources/db/migration/V3__seed_data.sql` — fixture data INSERT statements
- `src/main/resources/application.yml` — Flyway configuration
- `pom.xml` — `flyway-core` and `flyway-database-postgresql` dependencies

**Migration Strategy:** Forward-only SQL migrations, versioned with `V{N}__description.sql` naming. Flyway runs automatically on application startup, ensuring the PostgreSQL schema matches the application code at every deployment.

---

### D-008 — Spring Data JPA for Data Access

**Affected Components:**
- `src/main/java/com/cardemo/repository/*.java` — all 11 repository interfaces extending `JpaRepository`
- `src/main/java/com/cardemo/model/entity/*.java` — all 11 `@Entity` classes
- `src/main/java/com/cardemo/model/key/*.java` — 3 `@Embeddable` composite key classes

**VSAM Access Pattern Mapping:**
| VSAM Operation | JPA Equivalent |
|---|---|
| `READ` (keyed) | `repository.findById(key)` |
| `STARTBR` / `READNEXT` | `repository.findAll(Pageable)` with cursor-based filtering |
| `WRITE` | `repository.save(entity)` (INSERT) |
| `REWRITE` | `repository.save(entity)` (UPDATE via managed entity) |
| `DELETE` | `repository.delete(entity)` |
| AIX/PATH access | Custom `@Query` methods or derived query methods |

---

### D-009 — JPA @Version for Optimistic Locking

**Affected Components:**
- `src/main/java/com/cardemo/model/entity/Account.java` — `@Version` field
- `src/main/java/com/cardemo/model/entity/Card.java` — `@Version` field
- `src/main/java/com/cardemo/service/account/AccountUpdateService.java` — `OptimisticLockException` handling
- `src/main/java/com/cardemo/service/card/CardUpdateService.java` — `OptimisticLockException` handling
- `src/main/java/com/cardemo/exception/ConcurrentModificationException.java` — custom exception wrapping

**How It Works:** When `AccountUpdateService.updateAccount()` is called, JPA automatically includes `WHERE version = ?` in the `UPDATE` SQL. If another transaction has already modified the record (incrementing the version), the update affects zero rows and JPA throws `OptimisticLockException`. This exactly replicates the COBOL pattern in `COACTUPC.cbl` where the program compares the record read during `9100-GETACCT-REQUEST` with the record at update time.

---

### D-010 — Spring Security with HTTP Basic Authentication

**Affected Components:**
- `src/main/java/com/cardemo/config/SecurityConfig.java` — security filter chain, `httpBasic()` configuration, `BCryptPasswordEncoder` bean
- `src/main/java/com/cardemo/service/auth/AuthenticationService.java` — credential verification and token generation (UUID-based session token)
- `src/main/java/com/cardemo/controller/AuthController.java` — `POST /api/auth/signin` endpoint
- `src/main/java/com/cardemo/model/dto/SignOnRequest.java` — authentication request DTO
- `src/main/java/com/cardemo/model/dto/SignOnResponse.java` — authentication response with UUID token, userId, userType, routing info
- `src/main/java/com/cardemo/model/enums/UserType.java` — `ADMIN` / `USER` role enum

**CICS-to-Spring-Security Mapping:**
| CICS Concept | Spring Security Equivalent |
|---|---|
| `RETURN TRANSID COMMAREA` | HTTP Basic Auth credentials per request (stateless) |
| COMMAREA `CDEMO-USER-TYPE` | Spring Security granted authority `ROLE_ADMIN` or `ROLE_USER` |
| Transaction security | `@PreAuthorize` annotations on controller methods |
| Session timeout | Not applicable — each request is independently authenticated |

**Authentication Flow:**
1. Client sends `POST /api/auth/signin` with HTTP Basic Auth header (`Authorization: Basic base64(userId:password)`) and JSON body `{userId, password}`.
2. Spring Security's `httpBasic()` filter validates credentials against `UserDetailsService` backed by `UserSecurityRepository`.
3. `BCryptPasswordEncoder` verifies the password hash.
4. `AuthenticationService.authenticate()` returns `SignOnResponse` with a UUID session token, user type, and CICS-equivalent routing fields (`toTranId`, `toProgram`).
5. Subsequent API calls use HTTP Basic Auth (`-u userId:password`) for stateless authentication.

> **Note:** Can be extended to JWT token-based auth for production deployments if token refresh, expiry, or claims-based authorization is needed.

---

### D-011 — REST API for BMS Screen Replacement

**Affected Components:**
- `src/main/java/com/cardemo/controller/*.java` — all 8 REST controller classes
- `src/main/java/com/cardemo/model/dto/*.java` — all request/response DTO classes
- `docs/api-contracts.md` — REST API specification document

**BMS-to-REST Mapping:**
| BMS Map | REST Endpoint | HTTP Method |
|---|---|---|
| `COSGN00.bms` | `/api/auth/signin` | POST |
| `COACTVW.bms` | `/api/accounts/{id}` | GET |
| `COACTUP.bms` | `/api/accounts/{id}` | PUT |
| `COCRDLI.bms` | `/api/cards` | GET (paginated) |
| `COCRDSL.bms` | `/api/cards/{cardNumber}` | GET |
| `COCRDUP.bms` | `/api/cards/{cardNumber}` | PUT |
| `COTRN00.bms` | `/api/transactions` | GET (paginated) |
| `COTRN01.bms` | `/api/transactions/{id}` | GET |
| `COTRN02.bms` | `/api/transactions` | POST |
| `COBIL00.bms` | `/api/billing/pay` | POST |
| `CORPT00.bms` | `/api/reports/submit` | POST |
| `COMEN01.bms` | `/api/menu/{type}` (type=main) | GET |
| `COADM01.bms` | `/api/menu/{type}` (type=admin) | GET |
| `COUSR00.bms` | `/api/admin/users` | GET (paginated) |
| `COUSR01.bms` | `/api/admin/users` | POST |
| `COUSR02.bms` | `/api/admin/users/{id}` | PUT |
| `COUSR03.bms` | `/api/admin/users/{id}` | DELETE |

---

### D-012 — Testcontainers for Integration Testing

**Affected Components:**
- `src/test/java/com/cardemo/integration/**/*.java` — all integration test classes
- `src/main/resources/application-test.yml` — Testcontainers-specific configuration
- `pom.xml` — Testcontainers BOM and module dependencies

**Container Configuration:**
| Container | Image | Purpose |
|---|---|---|
| PostgreSQL | `postgres:16-alpine` | Real database for repository and batch integration tests |
| LocalStack | `localstack/localstack` | S3, SQS, SNS for AWS integration tests |

**Test Isolation Strategy:** Each test class gets its own container instances (via `static` container fields shared across test methods). `@DynamicPropertySource` injects container connection details into the Spring context. `@Transactional` on repository tests provides automatic rollback after each test method.

---

### D-013 — LocalStack for AWS Service Emulation

**Affected Components:**
- `docker-compose.yml` — LocalStack container service definition
- `localstack-init/init-aws.sh` — resource provisioning script
- `src/main/resources/application-local.yml` — LocalStack endpoint configuration
- `src/test/java/com/cardemo/integration/aws/*.java` — AWS integration tests

**Service Coverage:**
| AWS Service | LocalStack Feature | CardDemo Usage |
|---|---|---|
| S3 | Bucket operations, object CRUD | Batch file staging, statement output, report output |
| SQS | FIFO queue, send/receive/delete | Report submission queue (TDQ replacement) |
| SNS | Topic creation, publish, subscribe | Alert and notification publishing |

**Zero Live AWS Dependency Guarantee:** The `application-local.yml` and `application-test.yml` profiles configure all AWS endpoints to `http://localhost:4566`. No test, local development workflow, or CI pipeline step requires real AWS credentials or connectivity.

---

### D-014 — Maven for Build System

**Affected Components:**
- `pom.xml` — project build configuration
- `mvnw` / `mvnw.cmd` — Maven wrapper scripts
- `.mvn/wrapper/maven-wrapper.properties` — wrapper version configuration

**Build Lifecycle Mapping:**
| JCL Build Pattern (Source) | Maven Phase (Target) |
|---|---|
| `BATCMP.jcl` (batch COBOL compile) | `mvn compile` |
| `CICCMP.jcl` (CICS COBOL compile) | `mvn compile` |
| `BMSCMP.jcl` (BMS map compile) | N/A (REST DTOs replace BMS maps) |
| Link-edit step | `mvn package` (JAR assembly) |

---

### D-015 — Java 25 LTS

**Affected Components:**
- `pom.xml` — `<java.version>25</java.version>` and compiler plugin configuration
- `Dockerfile` — `eclipse-temurin:25-jdk` / `eclipse-temurin:25-jre` base images
- All `src/main/java/**/*.java` files — may use Java 25 language features

**Java 25 Features Leveraged:**
| Feature | Usage in CardDemo |
|---|---|
| Flexible constructor bodies (JEP 492) | Entity class constructors with validation |
| Pattern matching for `switch` (JEP 441, finalized) | Enum-based dispatching in service layer |
| Record patterns (JEP 440, finalized) | DTO decomposition in processors |
| Virtual threads (JEP 444, finalized) | Available for future enablement via `spring.threads.virtual.enabled=true` — not currently configured |
| Sequenced collections (JEP 431, finalized) | Ordered result sets in browse operations |

---

### D-016 — Spring Boot 3.5.x

**Affected Components:**
- `pom.xml` — `<parent>` spring-boot-starter-parent version
- All `src/main/java/com/cardemo/config/*.java` — Spring Boot auto-configuration
- `src/main/resources/application*.yml` — Spring Boot configuration properties

**Spring Boot 3.5.x Capabilities Used:**
| Capability | CardDemo Usage |
|---|---|
| Built-in structured logging | JSON log format via `logging.structured.format.console` |
| Observability auto-configuration | Micrometer + OTLP tracing without manual bean registration |
| Virtual thread support | Available for future enablement — not currently configured in `application.yml` |
| Jakarta EE 10 APIs | `jakarta.persistence.*`, `jakarta.validation.*` |
| Testcontainers integration | `@ServiceConnection` for automatic property injection |

---

### D-017 — Structured Logging with Correlation IDs

**Affected Components:**
- `src/main/resources/logback-spring.xml` — Logback configuration with JSON encoder
- `src/main/java/com/cardemo/observability/CorrelationIdFilter.java` — request correlation ID injection
- `src/main/java/com/cardemo/config/ObservabilityConfig.java` — observability bean configuration
- `pom.xml` — `logstash-logback-encoder` dependency

**Log Field Schema:**
| Field | Source | Purpose |
|---|---|---|
| `timestamp` | System clock | Event time in ISO 8601 |
| `level` | SLF4J | Log level (INFO, WARN, ERROR, DEBUG) |
| `logger` | Class name | Originating class |
| `message` | Application code | Human-readable event description |
| `traceId` | Micrometer Tracing | Distributed trace identifier |
| `spanId` | Micrometer Tracing | Current span identifier |
| `correlationId` | `CorrelationIdFilter` | Request-scoped unique identifier |
| `userId` | Security context | Authenticated user (when available) |

---

### D-018 — Micrometer + OpenTelemetry for Tracing

**Affected Components:**
- `src/main/java/com/cardemo/config/ObservabilityConfig.java` — tracing configuration
- `src/main/java/com/cardemo/observability/MetricsConfig.java` — custom business metrics
- `src/main/java/com/cardemo/observability/HealthIndicators.java` — composite health checks
- `src/main/resources/application.yml` — OTLP exporter endpoint, sampling rate
- `pom.xml` — `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `micrometer-registry-prometheus`
- `docker-compose.yml` — Jaeger container for local trace visualization

**Custom Business Metrics:**
| Metric Name | Type | Tags | Purpose |
|---|---|---|---|
| `carddemo.batch.records.processed` | Counter | `job` | Records processed per batch job |
| `carddemo.batch.records.rejected` | Counter | `job`, `reason` | Records rejected with reason codes |
| `carddemo.auth.attempts` | Counter | `result` (success/failure) | Authentication attempt tracking |
| `carddemo.transaction.amount.total` | DistributionSummary | `type` | Transaction amount distribution |

**Health Check Indicators:**
| Indicator | Target | Check Method |
|---|---|---|
| `db` | PostgreSQL | DataSource connection validation |
| `s3` | S3 (LocalStack) | Bucket existence check |
| `sqs` | SQS (LocalStack) | Queue attribute retrieval |

---

### D-019 — Layered Domain-Driven Refactoring

**Affected Components:**
- `src/main/java/com/cardemo/domain/constants/AccountConstants.java` — NEW — centralized account field constants (ACCOUNT_ID_LENGTH, SSN_LENGTH, PHONE_DIGITS_LENGTH, FICO_MIN, FICO_MAX)
- `src/main/java/com/cardemo/domain/constants/CardConstants.java` — NEW — centralized card field constants (CARD_NUM_MAX_LENGTH, MASK_VISIBLE_DIGITS, MIN/MAX_EXPIRY_YEAR, MIN/MAX_EXPIRY_MONTH)
- `src/main/java/com/cardemo/domain/constants/DateFormatConstants.java` — NEW — shared `DateTimeFormatter` instances (consolidated from 11 files)
- `src/main/java/com/cardemo/domain/constants/PaginationConstants.java` — NEW — centralized page size constants (USER_PAGE_SIZE=10, CARD_PAGE_SIZE=7, TRANSACTION_PAGE_SIZE=10)
- `src/main/java/com/cardemo/domain/constants/TransactionConstants.java` — NEW — centralized transaction constants (TRAN_ID_LENGTH, TRAN_ID_FORMAT, MAX_AMOUNT_SCALE)
- `src/main/java/com/cardemo/domain/constants/ValidationPatterns.java` — NEW — shared regex patterns (NUMERIC_PATTERN, ALPHA_SPACE_PATTERN)
- `src/main/java/com/cardemo/domain/validation/AccountValidator.java` — NEW — account field validation extracted from `AccountUpdateService`
- `src/main/java/com/cardemo/domain/validation/CardValidator.java` — NEW — card field validation extracted from `CardUpdateService`
- `src/main/java/com/cardemo/domain/validation/TransactionValidator.java` — NEW — transaction validation extracted from `TransactionAddService`
- `src/main/java/com/cardemo/domain/validation/UserValidator.java` — NEW — user field validation extracted from `UserAddService`/`UserUpdateService`
- `src/main/java/com/cardemo/domain/rules/CreditLimitRules.java` — NEW — credit limit checking extracted from `BillPaymentService`
- `src/main/java/com/cardemo/domain/rules/InterestCalculationRules.java` — NEW — `(balance × rate) / 1200` BigDecimal formula extracted from `InterestCalculationProcessor`
- `src/main/java/com/cardemo/domain/rules/TransactionPostingRules.java` — NEW — 4-stage validation cascade extracted from `TransactionPostingProcessor`
- `src/main/java/com/cardemo/service/interfaces/AccountService.java` — NEW — service contract for account operations
- `src/main/java/com/cardemo/service/interfaces/AdminService.java` — NEW — service contract for user administration
- `src/main/java/com/cardemo/service/interfaces/AuthService.java` — NEW — service contract for authentication
- `src/main/java/com/cardemo/service/interfaces/BillingService.java` — NEW — service contract for billing
- `src/main/java/com/cardemo/service/interfaces/CardService.java` — NEW — service contract for card operations
- `src/main/java/com/cardemo/service/interfaces/MenuService.java` — NEW — service contract for menu operations
- `src/main/java/com/cardemo/service/interfaces/ReportService.java` — NEW — service contract for report submission
- `src/main/java/com/cardemo/service/interfaces/TransactionService.java` — NEW — service contract for transaction operations
- `src/main/java/com/cardemo/config/GlobalExceptionHandler.java` — NEW — extracted from `WebConfig.java` (17 `@ExceptionHandler` methods). The 15 handlers originally nested in `WebConfig` were relocated verbatim; 2 additional handlers (`MethodArgumentTypeMismatchException` and `InvalidDataAccessApiUsageException`) were added as defensive hardening to map controller path-variable coercion failures and Spring-Data illegal-argument wrapping to consistent {@code ErrorResponse} payloads (HTTP 400). These additions do not alter any pre-existing endpoint contract and preserve the Javadoc-documented error-mapping semantics for every in-scope exception type.
- `src/main/java/com/cardemo/config/JacksonConfig.java` — NEW — extracted `Jackson2ObjectMapperBuilderCustomizer` from `WebConfig.java`
- `src/main/java/com/cardemo/config/WebConfig.java` — REDUCED — retains only CORS mappings and request logging filter
- `src/main/java/com/cardemo/service/**/*.java` — 20 service classes — implement `service/interfaces/*` contracts, delegate validation/rules to `domain/*`, use centralized constants
- `src/main/java/com/cardemo/batch/**/*.java` — 19 batch classes — use `DateFormatConstants`, delegate validation/rules to domain classes
- `src/main/java/com/cardemo/controller/*.java` — 8 controllers — inject service interfaces instead of concrete classes
- `src/main/resources/logback-spring.xml` — UPDATE — adds logger entry for `com.cardemo.domain`
- `README.md`, `docs/architecture-before-after.md`, `docs/onboarding-guide.md` — documentation of new layer

**Refactoring Scope Summary:**

| Dimension | Before Refactor | After Refactor |
|---|---|---|
| Packages under `com.cardemo` | 9 (config, controller, service, model, repository, batch, exception, observability, root) | 11 (adds `domain`, `service.interfaces`) |
| Service interfaces | 0 | 8 (one per domain) |
| Monolithic service classes (>500 lines) | 17 | Same (business logic preserved; only validation/rules delegated) |
| Duplicated `DateTimeFormatter` declarations | 11 files | 1 centralized file |
| Duplicated field-length constants | 26 files, ~67 declarations | 6 centralized constant classes |
| `WebConfig.java` size | 840 lines (3 mixed concerns) | Reduced to CORS + request logging only |
| `GlobalExceptionHandler` location | Nested inner class inside `WebConfig` | Standalone `@RestControllerAdvice` class |

**Design Patterns Applied:**
| Pattern | Rationale |
|---|---|
| Service Interface Pattern | Enables constructor injection by interface type; decouples controllers from concrete implementations; facilitates mock-based unit testing |
| Domain Validation Pattern | Extracts framework-independent validators that can be unit-tested without Spring context; reusable across services and batch processors |
| Domain Rules Pattern | Stateless rule classes (`CreditLimitRules`, `InterestCalculationRules`, `TransactionPostingRules`) encapsulate business calculations |
| Constant Holder Pattern | `final` classes with `private` constructors group related `public static final` constants by domain (Account, Card, Transaction, Date, Pagination, Validation) |
| Separation of Concerns Extraction | `WebConfig.java` decomposed into three single-responsibility classes: `WebConfig` (CORS + logging), `JacksonConfig` (serialization), `GlobalExceptionHandler` (error mapping) |

**Behavioral Preservation Guarantees:**
| Guarantee | Enforcement |
|---|---|
| 100% behavioral parity | No business logic rewritten; only relocation and delegation |
| All 19 REST endpoints unchanged | `docs/api-contracts.md` remains authoritative |
| All 22 features unchanged | F-001 through F-022 preserved |
| All 55 existing tests pass | Import-only updates; zero assertion changes |
| All 8 validation gates pass | `docs/validation-gates.md` unchanged |
| COBOL traceability preserved | Every extracted method carries Javadoc `@see` and paragraph-mapping comments |
| Mutable batch state preserved | `TransactionPostingProcessor` retains `goodTranCount`, `badTranCount`, `rejections` for Spring Batch step lifecycle |

**Constraints Honored:**
- No new dependencies added to `pom.xml` (Rule R-002)
- No microservice decomposition (Decisions D-005, D-010, D-014 preserved)
- No database schema changes
- No AWS infrastructure changes
- No Docker/deployment changes
- Single-phase atomic delivery (Rule R-010)

---

### D-020 — Spring Boot 3.5.11 → 3.5.12 Security Exception

**Affected Components:**
- `pom.xml` — `<parent>` spring-boot-starter-parent version pinned to `3.5.12`
- All transitively managed Spring dependencies — most notably `spring-security-core`, `spring-security-web`, `spring-security-config` reconciled to **6.5.9** via the Spring Boot 3.5.12 BOM
- `DECISION_LOG.md` D-016 — cross-references this exception

**Security Context:**
| Attribute | Value |
|---|---|
| CVE Identifier | CVE-2026-22732 |
| CVSS v3.1 Score | 9.1 (CRITICAL) |
| CWE | CWE-113 (HTTP Response Splitting) / CWE-20 (Improper Input Validation) |
| Affected Component | Spring Security `OnCommittedResponseWrapper` |
| Affected Versions | Spring Security 6.5.0 – 6.5.8 (bundled with Spring Boot 3.5.0 – 3.5.11) |
| Fixed In | Spring Security 6.5.9 (bundled with Spring Boot 3.5.12) |
| Attack Vector | Response-header manipulation via `setHeader`/`setIntHeader`/`addIntHeader` combined with `Content-Length` can bypass response-header controls when untrusted input is reflected into response headers |

**Alternatives Considered and Rejected:**

| Alternative | Why Rejected |
|---|---|
| (a) Remain on Spring Boot 3.5.11; apply runtime-only mitigation (e.g., `StrictHttpFirewall` configuration) | The project's OWASP `dependency-check-maven` plugin is configured with `failBuildOnCVSS=7`. Any CVSS ≥ 7.0 finding breaks the `mvn verify` pipeline regardless of runtime mitigation. Runtime mitigation does not remove the vulnerable `OnCommittedResponseWrapper` class from the classpath, so the scanner still fails. |
| (c) Upgrade to Spring Boot 3.6.x (minor version bump) | A minor version bump exceeds the scope of a security-only exception. Spring Boot 3.6.x includes configuration-property deprecations, auto-configuration changes, and potential binary-incompatible changes that would require broader regression testing against the 22-feature 100% COBOL-parity mandate (AAP §0.1.1). |
| (d) Suppress the CVE in `dependency-check-maven` `suppressions.xml` | CVSS 9.1 CRITICAL is above the project's risk-acceptance threshold. Suppression would hide an exploitable vulnerability rather than remediate it. |

**Selected Approach — Patch-Level Advance (3.5.11 → 3.5.12):**

The minimum change that (1) removes the vulnerable `spring-security-*` 6.5.0–6.5.8 artifacts from the classpath, (2) satisfies the `failBuildOnCVSS=7` gate, and (3) preserves all other dependency coordinates and behavioral contracts. Only the `<parent>` version tag in `pom.xml` is advanced; no explicit dependency-version overrides are added, and no Spring Boot configuration properties, auto-configuration classes, or starters are changed.

**Compatibility Verification:**

| Verification | Result |
|---|---|
| Full test suite (`./mvnw -B -ntp clean verify -Pintegration`) | 888 tests pass (729 unit + 159 IT/E2E) with zero failures and zero errors |
| JaCoCo coverage gate (≥80% line coverage) | All coverage checks met |
| Spring Boot 3.5.x binary compatibility policy | Patch-level advance within the same minor line guaranteed compatible by Spring Boot support policy |
| Application startup under `--spring.profiles.active=local` | Startup time within the Gate 1 threshold; all 27 service beans registered cleanly |
| REST endpoint contract stability | All 19 endpoints registered identically; no signature, path, or HTTP-method changes |
| Validation Gate 3 (batch throughput ≥100 records/sec, peak heap ≤512 MB) | Baselines preserved |

**AAP Deviation Disclosure:**

AAP §0.6.2 states "No external dependency additions or version changes are required." This decision constitutes an explicit, documented security exception to that clause. The AAP §0.1.1 reference to Spring Boot 3.5.11 is thereby superseded by this entry. Future patch-level advances follow the same process:

1. Identify new CVE affecting Spring Boot 3.5.x with CVSS ≥ 7.0
2. Record CVE ID, CVSS score, affected component, and fixed-in version
3. Advance `pom.xml` parent version by the minimum patch level that remediates the CVE
4. Run `./mvnw -B -ntp clean verify -Pintegration` to confirm all 888 tests pass unchanged
5. Add a new decision entry (`D-02N`) or append a row to this entry's history table documenting the advance

**Risks and Mitigations:**

| Risk | Mitigation |
|---|---|
| Cascading version drift (transitively upgrading unrelated libraries) | BOM-managed version reconciliation; no explicit `<dependency>` version overrides added |
| Baseline documentation drift (AAP §0.1.1 still states 3.5.11) | This decision entry plus updated D-016 rationale serve as the authoritative current-version reference |
| Behavioral regressions at the library level | Full 888-test suite continues to pass under 3.5.12; COBOL-parity E2E tests (3 files, 3,746 lines) pass unchanged |
| Future operator confusion (why does code ship with 3.5.12 when AAP says 3.5.11?) | D-020 is referenced from D-016, from `README.md`, and indirectly from operator-facing documentation |

---

## Appendix: Decision Categories

| Category | Decisions |
|---|---|
| **Data Precision & Integrity** | D-001 (BigDecimal), D-009 (Optimistic Locking) |
| **Security** | D-002 (BCrypt), D-010 (HTTP Basic Authentication) |
| **Cloud Services** | D-003 (S3 for GDG), D-004 (SQS for TDQ), D-013 (LocalStack) |
| **Batch Processing** | D-005 (Spring Batch) |
| **Data Persistence** | D-006 (PostgreSQL), D-007 (Flyway), D-008 (Spring Data JPA) |
| **API Design** | D-011 (REST API) |
| **Testing** | D-012 (Testcontainers) |
| **Build & Runtime** | D-014 (Maven), D-015 (Java 25), D-016 (Spring Boot 3.5.x), D-020 (Spring Boot 3.5.11 → 3.5.12 Security Exception) |
| **Observability** | D-017 (Structured Logging), D-018 (Micrometer + OpenTelemetry) |
| **Code Architecture** | D-019 (Layered Domain-Driven Refactoring) |
