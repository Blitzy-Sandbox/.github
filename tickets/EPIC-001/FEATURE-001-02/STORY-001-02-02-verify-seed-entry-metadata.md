# Verify seed-entry metadata against the published schema

## User Story

> As a Catalog Reviewer responsible for the prompt catalog's launch quality,
> I want a verification checklist that tests every seed entry's metadata against the 8-field schema before publication,
> So that all 6 published seed entries conform to the schema with zero defects on launch day and contributors landing on the catalog see only schema-valid examples.

### INVEST Validation

This story satisfies all six INVEST criteria:

- [x] **Independent** — Once [`./STORY-001-02-01-document-six-seed-prompts.md`](./STORY-001-02-01-document-six-seed-prompts.md) produces the 6 seed entries and [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) publishes the schema, this verification story executes without coupling to other in-flight work.
- [x] **Negotiable** — The 4-step verification rubric, the per-entry pass/fail report format, and the launch-blocking policy are starting proposals open to refinement during sprint planning.
- [x] **Valuable** — Provides launch-quality assurance: zero non-conformant seed entries reach publication, eliminating reviewer rework and protecting the catalog's first-impression credibility.
- [x] **Estimable** — Scope is bounded to running 4 deterministic schema checks against 6 known entries and producing 1 verification report; size is concrete enough to estimate.
- [x] **Sized appropriately** — Executing the 4-step verification on 6 entries and authoring the verification report is completable within 1 business day inside a single sprint.
- [x] **Testable** — Acceptance criteria specify concrete pass/fail conditions on per-entry conformance, report structure, and launch-block behavior.

### Demo-able

This story is demo-able to the Product Owner.

Demo scenario: The Catalog Reviewer opens the verification report on GitHub and walks the Product Owner through (1) the 4 schema-conformance checks run against each of the 6 seed entries (24 total check executions), (2) the per-entry pass/fail table showing every entry passing all 4 checks, (3) the explicit launch-block clause stating that any single non-conformant entry blocks publication of the entire seed batch, and (4) the cross-link to the schema document at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md). The Product Owner verifies the report renders on github.com without broken links and confirms acceptance.

## Acceptance Criteria

This Story declares 4 acceptance criteria — the lower bound of the 4–8 range — and uses a 1:1 mapping between criteria and the 4 required-coverage categories: input validation (AC-1), expected output/behavior (AC-2), error handling (AC-3), and edge case handling (AC-4). Each criterion uses the BDD `Given/When/Then` format with a single trigger per `When` clause, and every numeric, length, or count claim is expressed as a concrete measurable value.

### AC-1: Verification rejects an entry whose metadata block omits 1 or more of the 8 required fields

- **Given** a seed entry whose metadata block omits 1 or more of the 8 required fields defined by the schema at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md)
- **When** the Catalog Reviewer applies Step 1 (Required Field Presence Check) of the verification rubric to that entry
- **Then** the verification report marks the entry Non-Conformant, lists every missing field name, and records the entry's slug as a launch-blocker

### AC-2: Verification produces a per-entry pass/fail report covering all 6 seed entries

- **Given** the 6 published seed entries authored by [`./STORY-001-02-01-document-six-seed-prompts.md`](./STORY-001-02-01-document-six-seed-prompts.md) and the schema at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md)
- **When** the Catalog Reviewer runs the 4-step verification rubric across all 6 entries
- **Then** the verification report contains exactly 6 rows (one per entry) with columns for entry slug, Step 1 result (Pass/Fail), Step 2 result, Step 3 result, Step 4 result, and an overall verdict, and every Pass row records a timestamp in ISO 8601 format

### AC-3: Verification flags an entry whose field value violates the declared data type

- **Given** a seed entry whose `tags` field is encoded as a comma-separated string instead of the schema-declared `array<string>` data type
- **When** the Catalog Reviewer applies Step 2 (Data-Type Conformance Check) of the verification rubric to that entry
- **Then** the verification report marks the entry Non-Conformant, names the offending field as `tags`, records the declared data type as `array<string>` and the observed data type as `string`, and recommends returning the entry to the curator for correction within 2 business days

### AC-4: Verification flags an entry whose description length sits outside the inclusive 50–500 character range

- **Given** a seed entry whose `description` field has a length of 49 characters or 501 characters (1 character outside the inclusive 50–500 range declared by the schema)
- **When** the Catalog Reviewer applies Step 3 (Length Constraint Check) of the verification rubric to that entry
- **Then** the verification report marks the entry Non-Conformant, names the field as `description`, records the observed length and the inclusive 50–500 character bound, and lists the entry's slug as a launch-blocker

## Sub-Tasks

Sub-Tasks are owned by individuals (use `@assignee` placeholders); the Story itself is owned by the Team.

- [ ] Author the 4-step verification rubric document covering Required Field Presence, Data-Type Conformance, Length Constraint, and Worked-Example Format checks @assignee
- [ ] Run the 4 schema-conformance checks against each of the 6 seed entries published by [`./STORY-001-02-01-document-six-seed-prompts.md`](./STORY-001-02-01-document-six-seed-prompts.md) and record per-entry results @assignee
- [ ] Produce the per-entry pass/fail verification report with columns slug, Step 1, Step 2, Step 3, Step 4, and overall verdict, formatted as a Markdown table with 6 rows @assignee
- [ ] Verify all relative-path links to [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) and [`./STORY-001-02-01-document-six-seed-prompts.md`](./STORY-001-02-01-document-six-seed-prompts.md) render on github.com without broken targets @assignee

## Edge Cases

This Story documents 3 edge cases covering the 3 mandatory categories (Empty/Null Input, Boundary Values, Invalid Input). The Concurrent/Conflicting Operations category is intentionally omitted because verification is a deterministic read of static metadata against a static schema — no runtime concurrency exists. This omission is permitted under Rule C-8's "If applicable" provision.

| Category | Required? | Scenario |
|---|---|---|
| Empty/Null Input | Mandatory | A seed entry submits with all 8 metadata fields populated as empty strings — the verification's Step 1 (Required Field Presence Check) marks the entry Non-Conformant for every field that fails the schema's minimum-length constraint of 1 character per AC-1 |
| Boundary Values | Mandatory | A seed entry submits with a `description` field of exactly 50 characters and another submits with exactly 500 characters (the inclusive minimum and maximum) — verification's Step 3 (Length Constraint Check) marks both entries Conformant per AC-4 because the schema declares the bounds as inclusive |
| Invalid Input | Mandatory | A seed entry submits with the `tags` field encoded as a comma-separated string instead of `array<string>` — verification's Step 2 (Data-Type Conformance Check) marks the entry Non-Conformant per AC-3 |

### Detailed Edge Case Scenarios

**Edge Case 1: Empty/Null Input — Seed entry with empty-string field values**

The verification reviewer encounters a candidate seed entry where all 8 required metadata fields are present in the entry block but each field's value is the empty string. Step 1 (Required Field Presence Check) treats the empty string as a length-1 violation against the schema's per-field minimum-length constraint (1 character for `string` fields, 1 element for `array<string>` fields, 50 characters for the `description` field). The entry is marked Non-Conformant, every empty field is named in the report, and the entry's slug is recorded as a launch-blocker per AC-1.

**Edge Case 2: Boundary Values — Seed entry at the inclusive description-length boundaries**

The verification reviewer encounters one seed entry whose `description` field is exactly 50 characters (the schema's inclusive minimum) and a second whose `description` field is exactly 500 characters (the schema's inclusive maximum). Step 3 (Length Constraint Check) marks both entries Conformant because the schema declares the 50–500 character range as inclusive on both endpoints. The verification report records each entry's observed `description` length and the verdict Pass for Step 3.

**Edge Case 3: Invalid Input — Seed entry with type-mismatched tags field**

The verification reviewer encounters a seed entry whose `tags` field is encoded as the comma-separated string `"foo,bar,baz"` instead of the schema-declared `array<string>` data type. Step 2 (Data-Type Conformance Check) marks the entry Non-Conformant, the per-field error report names `tags` with declared type `array<string>` and observed type `string`, and the verification report recommends returning the entry to the curator for correction within 2 business days per AC-3.

## Dependencies

- Parent Feature: [`FEATURE-001-02`](../FEATURE-001-02-publish-initial-seed-catalog-entries.md)
- Prerequisite Story (entries to verify): [`STORY-001-02-01`](./STORY-001-02-01-document-six-seed-prompts.md) — the 6 seed entries authored by this sibling Story are the verification input
- Prerequisite Story (schema reference): [`STORY-001-01-01`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) — the 8-field metadata schema that defines every conformance check executed by the verification rubric
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) — the originating roadmap item "Prompt catalog with examples and snippets"
- Shared Schema: the 8-field metadata schema published by `STORY-001-01-01` is referenced normatively in AC-1, AC-3, AC-4, and all 3 edge cases

## Story Estimation Guidance

### Effort

Low — Running 4 deterministic schema checks against 6 entries (24 check executions total) and authoring 1 verification report. Estimated less than 1 day of focused work including review.

### Complexity

Low — The 4 verification steps are deterministic schema-conformance checks; there is no novel logic, no integration with external services, and no data transformation. The schema document at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) provides the complete pass/fail criteria.

### Uncertainty

Low — The 6 seed entries (input) and the 8-field schema (rules) are both fully specified by upstream Stories before this verification work begins. No unknowns remain at sprint start.

### Story Points

Suggested points: **2** from the Fibonacci set `{1, 2, 3, 5, 8, 13}`. Rationale: Low effort × Low complexity × Low uncertainty places this story at the small end of the team's relative-sizing scale. It matches the 2-point sizing of [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) because both are tightly scoped, single-document deliverables with no integration prerequisites at execution time. It is smaller than the 5-point sizing of sibling [`./STORY-001-02-01-document-six-seed-prompts.md`](./STORY-001-02-01-document-six-seed-prompts.md) because verification only reads existing entries while sibling authors 6 new entries with full metadata.

## Definition of Done (Story-Level)

- [ ] Code passes linting/static analysis
- [ ] Unit tests cover new functionality (minimum 80% coverage)
- [ ] Integration tests validate acceptance criteria (all Given/When/Then scenarios pass)
- [ ] Documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
- [ ] Story is demo-able to Product Owner
