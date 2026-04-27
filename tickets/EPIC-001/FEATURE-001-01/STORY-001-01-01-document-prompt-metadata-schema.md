# Document prompt metadata schema for catalog entries

## User Story

> As a Sandbox Content Curator preparing the prompt catalog for first publication,
> I want a documented metadata schema that specifies the required fields, data types, and length constraints for every catalog entry,
> So that downstream contributors can author conformant entries on day one and the reviewer rubric in [`./STORY-001-01-02-define-contribution-review-workflow.md`](./STORY-001-01-02-define-contribution-review-workflow.md) can deterministically pass or fail submissions against the schema.

### INVEST Validation

This story satisfies all six INVEST criteria:

- [x] **Independent** — This is the foundational schema document; it has zero prerequisite Stories. Sibling and downstream Stories depend on its output, but it itself depends on no other Story.
- [x] **Negotiable** — The 8 field set, the data types, and the length constraints are starting proposals open to refinement during sprint planning before commitment.
- [x] **Valuable** — Provides every downstream contributor a single, unambiguous schema, eliminating guesswork about required catalog metadata and unblocking 3 downstream Stories.
- [x] **Estimable** — Scope is bounded to one Markdown schema document with 8 named fields and per-field constraints; size is concrete enough to estimate.
- [x] **Sized appropriately** — Authoring 1 schema document of at most 600 words is completable within a single sprint.
- [x] **Testable** — Acceptance criteria specify concrete pass/fail conditions on field count, data types, length bounds, and document structure.

### Demo-able

This story is demo-able to the Product Owner.

Demo scenario: The Content Curator opens the published schema document on GitHub and walks the Product Owner through (1) the table of 8 required metadata fields with their data types and length constraints, (2) the worked example showing a valid entry that satisfies every field constraint, (3) the explicit reference to the originating roadmap item at [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134), and (4) the cross-link to the downstream review workflow at [`./STORY-001-01-02-define-contribution-review-workflow.md`](./STORY-001-01-02-define-contribution-review-workflow.md). The Product Owner verifies that the schema renders on github.com without broken links and confirms acceptance.

## Acceptance Criteria

This Story declares 5 acceptance criteria that collectively cover all 4 required-coverage categories: input validation (AC-2), expected output/behavior (AC-1, AC-4, AC-5), error handling (AC-3), and edge case handling (AC-2 plus the dedicated Edge Cases section). Each criterion uses the BDD `Given/When/Then` format with a single trigger per `When` clause.

### AC-1: Schema document defines exactly 8 required metadata fields with names, data types, and constraints

- **Given** a contributor opens the published schema document on GitHub
- **When** the contributor reads the "Required Fields" Markdown table
- **Then** the table contains exactly 8 rows, each row specifies the field name, the data type (one of `string`, `integer`, `array<string>`), the minimum length or value, and the maximum length or value, and the field-name column uses lowercase kebab-case identifiers

### AC-2: Schema document rejects an entry that omits 1 or more required fields

- **Given** a candidate catalog entry whose metadata block omits 1 or more of the 8 required fields defined by this schema
- **When** a reviewer applies the schema's "Required Field Presence Rule" to the candidate entry
- **Then** the schema document instructs the reviewer to mark the entry Non-Conformant, list each missing field name in the rejection message, and stop further validation steps

### AC-3: Schema document defines a malformed-data-type response for fields that violate their declared type

- **Given** a candidate catalog entry that includes all 8 required fields but the value for at least 1 field violates the declared data type (for example, a `string` field receives an integer)
- **When** a reviewer applies the schema's "Data-Type Conformance Rule" to the candidate entry
- **Then** the schema document instructs the reviewer to mark the entry Non-Conformant, return a per-field report listing the field name, the declared data type, and the actual data type observed, and stop further validation steps

### AC-4: Schema document specifies maximum field-length boundaries for every string and array field

- **Given** a contributor reviews the published schema document for the 8 required fields
- **When** the contributor reads the "Length Constraints" column of the Required Fields table
- **Then** every `string` field declares a maximum length of 255 characters, every `array<string>` field declares a maximum element count of 10, the `description` field declares a length range of 50 to 500 characters, and the constraints are encoded as inclusive numeric bounds

### AC-5: Schema document publishes a worked example entry that conforms to every field constraint

- **Given** the published schema document
- **When** a contributor reads the "Worked Example" section
- **Then** the section contains exactly 1 worked example showing all 8 required fields populated with concrete values, every value satisfies the declared length and data-type constraints, and the example is rendered as a fenced code block with the `yaml` language identifier

## Sub-Tasks

Sub-Tasks are owned by individuals (use `@assignee` placeholders); the Story itself is owned by the Team.

- [ ] Draft the schema document with the 8 required fields, their data types, and their length constraints @assignee
- [ ] Document the Required Field Presence Rule and the Data-Type Conformance Rule with concrete pass/fail conditions @assignee
- [ ] Author the Worked Example section showing 1 conformant entry encoded as a `yaml` fenced code block @assignee
- [ ] Cross-link the schema to the originating roadmap item at [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) and to the parent Feature [`../FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md`](../FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md) @assignee
- [ ] Verify all relative-path links render on github.com and the worked example satisfies every length and data-type constraint @assignee

## Edge Cases

This Story documents 3 edge cases covering the 3 mandatory categories (Empty/Null Input, Boundary Values, Invalid Input). The Concurrent/Conflicting Operations category is omitted because the schema is a static specification with no runtime state — concurrency is not applicable.

| Category | Required? | Scenario |
|---|---|---|
| Empty/Null Input | Mandatory | A candidate entry submits with all 8 metadata fields populated as empty strings — the schema's minimum-length constraint (1 character) marks every field Non-Conformant per AC-2 and AC-4 |
| Boundary Values | Mandatory | A candidate entry submits with a `description` field of exactly 50 characters (the inclusive minimum) and another candidate submits with exactly 500 characters (the inclusive maximum) — both pass the length constraint defined in AC-4 |
| Invalid Input | Mandatory | A candidate entry submits with a `tags` field encoded as a comma-separated string instead of `array<string>` — the schema's Data-Type Conformance Rule marks the entry Non-Conformant per AC-3 |

### Detailed Edge Case Scenarios

**Edge Case 1: Empty/Null Input — Entry with empty-string field values**

A contributor submits a candidate entry where all 8 required fields are present but every field's value is the empty string. The schema's per-field minimum-length constraint (1 character for `string` fields, 1 element for `array<string>` fields, 50 characters for the `description` field) marks each empty value as Non-Conformant, and the entry is rejected with a per-field length-violation report listing every offending field name.

**Edge Case 2: Boundary Values — Entry at the inclusive description-length boundaries**

A contributor submits one candidate entry whose `description` field is exactly 50 characters (the declared inclusive minimum) and submits a second candidate whose `description` field is exactly 500 characters (the declared inclusive maximum). Both entries pass the schema's length constraint defined in AC-4. The schema document explicitly states that the bounds are inclusive so contributors and reviewers reach the same conclusion without ambiguity.

**Edge Case 3: Invalid Input — Entry with type-mismatched tags field**

A contributor submits a candidate entry whose `tags` field contains a comma-separated string ("foo,bar,baz") instead of the declared `array<string>` data type. The schema's Data-Type Conformance Rule (referenced in AC-3) marks the entry Non-Conformant, the per-field report names `tags` with declared type `array<string>` and observed type `string`, and the entry is returned to the contributor for correction.

## Dependencies

- Parent Feature: [`FEATURE-001-01`](../FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md)
- Prerequisite Stories: none — this is the foundational schema Story; no Stories must complete before it
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) — the originating roadmap item "Prompt catalog with examples and snippets"
- Downstream Consumers (informational): [`./STORY-001-01-02-define-contribution-review-workflow.md`](./STORY-001-01-02-define-contribution-review-workflow.md) references this schema in its Step 1 Schema Conformance Check; cross-feature Stories under `../FEATURE-001-02/` author and verify entries against this schema

## Story Estimation Guidance

### Effort

Low — A single Markdown schema document of about 600 words, with 1 Required Fields table (8 rows × 5 columns), 2 named conformance rules, and 1 worked example. Estimated less than 1 day of focused authoring including review.

### Complexity

Low — The 8 metadata fields and their constraints are well-understood from existing prompt-engineering practice; no novel research is required and no integration with external systems is in scope.

### Uncertainty

Low — The deliverable is a static specification document with no runtime state, no external dependencies, and no prerequisite Stories. The roadmap context at [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) is fully documented.

### Story Points

Suggested points: **2** from the Fibonacci set `{1, 2, 3, 5, 8, 13}`. Rationale: Low effort × Low complexity × Low uncertainty places this story at the small end of the team's relative-sizing scale. It is larger than a 1-point trivial story (e.g., a typo fix) because it produces a referenceable specification consumed by 3 downstream Stories, but smaller than a 3-point story (which is the sibling [`STORY-001-01-02`](./STORY-001-01-02-define-contribution-review-workflow.md) size) because it has no integration with the existing 11-step contributing workflow and no external prerequisites.

## Definition of Done (Story-Level)

- [ ] Code passes linting/static analysis
- [ ] Unit tests cover new functionality (minimum 80% coverage)
- [ ] Integration tests validate acceptance criteria (all Given/When/Then scenarios pass)
- [ ] Documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
- [ ] Story is demo-able to Product Owner
