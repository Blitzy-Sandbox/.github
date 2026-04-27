# Define contribution review workflow for catalog submissions

## User Story

> As a Catalog Maintainer responsible for reviewing prompt catalog submissions,
> I want a documented review workflow that specifies evaluation steps and acceptance criteria,
> So that every submission is assessed against the same 5-step rubric and reviewed within 5 business days of submission.

### INVEST Validation

This story satisfies all six INVEST criteria:

- [x] **Independent** — The review workflow is a self-contained document; once the schema ([`./STORY-001-01-01-document-prompt-metadata-schema.md`](./STORY-001-01-01-document-prompt-metadata-schema.md)) exists, this story can be authored without coupling to other in-flight stories.
- [x] **Negotiable** — The 5-step rubric, escalation path, and 5-business-day SLA are starting proposals open to refinement during sprint planning.
- [x] **Valuable** — Provides Catalog Maintainers a single, repeatable rubric, reducing review variance and shortening submission turnaround.
- [x] **Estimable** — Scope is bounded to one Markdown workflow document with 5 review steps and 1 escalation path; size is concrete enough to estimate.
- [x] **Sized appropriately** — Authoring 1 workflow document totaling at most 800 words is completable within a single sprint.
- [x] **Testable** — Acceptance criteria specify concrete pass/fail conditions on document structure, content, and link targets.

### Demo-able

This story is demo-able to the Product Owner.

Demo scenario: The Catalog Maintainer opens the published review-workflow document on GitHub and walks the Product Owner through (1) the 5 numbered review steps, (2) the explicit cross-link to the existing 11-step contributing workflow at [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106), (3) the escalation path to a backup reviewer, and (4) the 5-business-day SLA. The Product Owner verifies that the workflow renders on github.com without broken links and confirms acceptance.

## Acceptance Criteria

This Story declares 5 acceptance criteria that collectively cover all 4 required-coverage categories: input validation (AC-2), expected output/behavior (AC-1, AC-5), error handling (AC-3), and edge case handling (AC-4 plus the dedicated Edge Cases section). Each criterion uses the BDD `Given/When/Then` format with a single trigger per `When` clause.

### AC-1: Workflow document lists exactly 5 numbered review steps for a valid submission

- **Given** a submission that includes all 8 required metadata fields defined by [`./STORY-001-01-01-document-prompt-metadata-schema.md`](./STORY-001-01-01-document-prompt-metadata-schema.md) and the catalog maintainer opens the review-workflow document
- **When** the maintainer reads the "Review Steps" section
- **Then** the section contains exactly 5 numbered steps, each with a one-sentence description, an explicit step owner role, and a target completion time measured in business days

### AC-2: Workflow document rejects a submission whose metadata is missing 1 or more required fields

- **Given** a submission whose metadata excludes 1 or more of the 8 required fields specified by [`./STORY-001-01-01-document-prompt-metadata-schema.md`](./STORY-001-01-01-document-prompt-metadata-schema.md)
- **When** the maintainer reaches Step 1 (Schema Conformance Check) of the workflow
- **Then** the workflow document instructs the maintainer to mark the submission as Rejected, return it to the contributor with a list of missing field names, and stop further review

### AC-3: Workflow document defines an escalation path when the assigned reviewer does not respond within 5 business days

- **Given** a submission assigned to a primary reviewer at submission time T₀ and the primary reviewer has not posted a review decision by T₀ + 5 business days
- **When** any contributor or maintainer invokes the documented escalation procedure
- **Then** the workflow document names exactly 1 backup reviewer role and specifies that the backup reviewer takes ownership within 2 business days, recording the reassignment in the submission's review log

### AC-4: Workflow document specifies the maximum number of concurrent reviews per maintainer

- **Given** a maintainer who already holds 5 in-flight reviews in the Open state
- **When** a new submission arrives requesting that maintainer as the primary reviewer
- **Then** the workflow document specifies that the new submission is automatically reassigned to a backup reviewer, the maintainer's queue depth limit is 5 concurrent reviews, and the reassignment is logged with the submission identifier and timestamp

### AC-5: Workflow document cross-links to the existing 11-step contributing workflow

- **Given** the published review-workflow document on GitHub
- **When** a contributor reads the "Integration with Repository Workflow" section
- **Then** the section contains exactly 1 relative-path link to [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106) and a 1-sentence statement that the catalog review workflow extends — and does not replace — the established 11-step repository workflow

## Sub-Tasks

Sub-Tasks are owned by individuals (use `@assignee` placeholders); the Story itself is owned by the Team.

- [ ] Draft the review workflow document with 5 numbered review steps and a 5-step rubric @assignee
- [ ] Cross-link the workflow document to the existing 11-step contributing workflow at [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106) using a relative path @assignee
- [ ] Document the backup-reviewer escalation path with 5-business-day primary SLA and 2-business-day backup pickup window @assignee
- [ ] Specify the maximum concurrent-review queue depth of 5 per maintainer with reassignment behavior @assignee
- [ ] Verify all relative-path links render on github.com and the document validates against the 8-field metadata schema from [`./STORY-001-01-01-document-prompt-metadata-schema.md`](./STORY-001-01-01-document-prompt-metadata-schema.md) @assignee

## Edge Cases

This Story documents 3 edge cases covering the 3 mandatory categories (Empty/Null Input, Boundary Values, Invalid Input). The Concurrent/Conflicting Operations category is omitted from the table because AC-4 already specifies concurrent-review queue depth handling at the acceptance-criteria level.

| Category | Required? | Scenario |
|---|---|---|
| Empty/Null Input | Mandatory | A submission arrives with all 8 metadata fields populated as empty strings — workflow Step 1 (Schema Conformance Check) marks the submission Rejected because empty strings violate the schema's non-empty constraint |
| Boundary Values | Mandatory | A maintainer holds exactly 5 concurrent reviews when a new submission arrives — the queue-depth boundary triggers automatic reassignment to a backup reviewer per AC-4 |
| Invalid Input | Mandatory | A submission contains metadata fields with values that violate the schema's data-type constraints (e.g., a numeric field receives the string "TBD") — workflow Step 1 marks the submission Rejected with a field-by-field error report |

### Detailed Edge Case Scenarios

**Edge Case 1: Empty/Null Input — Submission with empty metadata fields**

A contributor submits a catalog entry where all 8 required metadata fields contain empty strings. At Step 1 (Schema Conformance Check), the maintainer detects that none of the fields satisfy the schema's non-empty length constraint (minimum 1 character per field) and marks the submission as Rejected. The submission is returned to the contributor with a 1-line message listing the 8 empty fields by name; no further review steps are executed.

**Edge Case 2: Boundary Values — Maintainer at the queue-depth boundary**

A primary reviewer is currently assigned to exactly 5 in-flight reviews when a 6th submission requests that reviewer. The workflow's queue-depth limit (5) is reached but not exceeded. Per AC-4, the system reassigns the new submission to the named backup reviewer and records the reassignment in the submission's review log with the submission identifier and the reassignment timestamp.

**Edge Case 3: Invalid Input — Submission with type-mismatched metadata**

A contributor submits a catalog entry where a metadata field defined as a numeric integer in the schema receives the string value "TBD". At Step 1 (Schema Conformance Check), the maintainer detects the data-type mismatch, marks the submission as Rejected, and returns a field-by-field error report listing each field name, the expected data type, and the actual value provided. No further review steps are executed.

## Dependencies

- Parent Feature: [`FEATURE-001-01`](../FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md)
- Prerequisite Story: [`STORY-001-01-01`](./STORY-001-01-01-document-prompt-metadata-schema.md) — the metadata schema must be defined before the review workflow can reference its 8 required fields
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106) — the established 11-step contributing workflow that this catalog review workflow extends
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) — the originating roadmap item "Prompt catalog with examples and snippets"
- Shared Schema: the 8-field metadata schema published by `STORY-001-01-01` is referenced normatively in AC-1, AC-2, and Edge Case 1

## Story Estimation Guidance

### Effort

Low/Medium — A single Markdown workflow document of about 800 words, with 5 review steps, 1 escalation path, and an integration cross-link. Estimated 1–2 days of focused authoring including review.

### Complexity

Medium — The workflow integrates with (does not replace) the existing 11-step contributing workflow at [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106), so authors must read and reference the established repository workflow accurately.

### Uncertainty

Low — The existing 11-step workflow is fully documented in the repository and the schema dependency `STORY-001-01-01` will be completed before this story starts.

### Story Points

Suggested points: **3** from the Fibonacci set `{1, 2, 3, 5, 8, 13}`. Rationale: this story is meaningfully larger than [`STORY-001-01-01`](./STORY-001-01-01-document-prompt-metadata-schema.md) (a 2-point schema document) because it must integrate with the 11-step repository workflow, but smaller than a 5-point story because the scope is bounded to a single workflow document with 5 review steps and 1 escalation path.

## Definition of Done (Story-Level)

- [ ] Code passes linting/static analysis
- [ ] Unit tests cover new functionality (minimum 80% coverage)
- [ ] Integration tests validate acceptance criteria (all Given/When/Then scenarios pass)
- [ ] Documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
- [ ] Story is demo-able to Product Owner
