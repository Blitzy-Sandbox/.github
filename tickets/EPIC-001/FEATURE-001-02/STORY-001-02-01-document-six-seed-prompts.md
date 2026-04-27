# Document six seed prompts sourced from existing contributor usage

## User Story

> As a Sandbox Content Curator preparing the prompt catalog for first publication,
> I want exactly 6 seed catalog entries authored against the 8-field metadata schema and grounded in existing contributor usage,
> So that the catalog launches with a non-empty body of conformant reference content and downstream contributors landing on day one see concrete worked examples instead of an empty catalog.

### INVEST Validation

This story satisfies all six INVEST criteria:

- [x] **Independent** — Once [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) publishes the schema, this seed-authoring Story executes without coupling to other in-flight work. Its outputs are consumed by sibling [`./STORY-001-02-02-verify-seed-entry-metadata.md`](./STORY-001-02-02-verify-seed-entry-metadata.md) but the dependency runs in only one direction.
- [x] **Negotiable** — The selected 6 prompts, the metadata field values for each, and the seed-batch publication policy are starting proposals open to refinement during sprint planning before commitment.
- [x] **Valuable** — Provides every catalog visitor a non-empty body of 6 worked examples on launch day, replacing an empty-state placeholder with concrete, schema-conformant reference content that contributors can study and remix.
- [x] **Estimable** — Scope is bounded to authoring 6 entries × 8 metadata fields = 48 metadata values plus 6 prompt bodies; size is concrete enough to estimate.
- [x] **Sized appropriately** — Authoring 6 catalog entries with full metadata totaling at most 3000 words is completable within a single sprint by 1 curator with reviewer support.
- [x] **Testable** — Acceptance criteria specify concrete pass/fail conditions on entry count, schema conformance, source attribution, and uniqueness.

### Demo-able

This story is demo-able to the Product Owner.

Demo scenario: The Sandbox Content Curator opens the seed catalog index on GitHub and walks the Product Owner through (1) the 6 published seed entries, each rendered as a Markdown file with the 8 required metadata fields populated and the prompt body following, (2) the per-entry source attribution citing the contributor handle and the originating prompt usage, (3) the cross-link to the metadata schema document at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) confirming each entry conforms to the 8-field schema, and (4) the cross-link to the originating roadmap item at [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134). The Product Owner verifies the index renders on github.com without broken links and confirms acceptance.

## Acceptance Criteria

This Story declares 6 acceptance criteria — within the 4–8 range and demonstrating an upper-mid count that exceeds the 4-category minimum. The 6 criteria collectively cover all 4 required-coverage categories: input validation (AC-1), expected output/behavior (AC-2, AC-5, AC-6), error handling (AC-3), and edge case handling (AC-4). Each criterion uses the BDD `Given/When/Then` format with a single trigger per `When` clause, and every numeric, length, or count claim is expressed as a concrete measurable value.

### AC-1: Submission rejects a candidate seed entry that omits 1 or more of the 8 required metadata fields

- **Given** a candidate seed entry whose metadata block omits 1 or more of the 8 required fields defined by [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md)
- **When** the curator runs the schema's Required Field Presence Rule against the candidate entry
- **Then** the candidate is rejected from the seed batch, the curator records the missing field names in the rejection log, and the candidate is excluded from the count toward the 6-entry target

### AC-2: Seed batch publishes exactly 6 entries that satisfy every schema constraint

- **Given** the 8-field metadata schema published by [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) and a queue of candidate seed entries authored by the curator
- **When** the curator finalizes the seed batch for publication
- **Then** the seed batch contains exactly 6 entries, each entry populates all 8 required metadata fields, every field value satisfies the declared data-type and length constraints (string fields ≤ 255 characters; description field within 50–500 characters; array fields ≤ 10 elements), and every entry is stored as a Markdown file under `tickets/` adjacent to the parent Feature

### AC-3: Seed batch handles a candidate entry whose `description` field violates the 50-character minimum

- **Given** a candidate seed entry whose `description` field has a length below 50 characters (the schema's inclusive minimum) but every other field satisfies the schema
- **When** the curator runs the schema's Length Constraint Rule against the candidate entry
- **Then** the candidate is rejected from the seed batch, the rejection log records the field name `description` and the observed character count, and the curator returns the candidate to the source contributor with a request to expand the description to a length within the inclusive 50–500 character range

### AC-4: Seed batch handles boundary-length descriptions at exactly 50 and exactly 500 characters

- **Given** 1 candidate seed entry whose `description` field has a length of exactly 50 characters and 1 candidate whose `description` has a length of exactly 500 characters (the schema's inclusive minimum and maximum)
- **When** the curator runs the schema's Length Constraint Rule against both candidates
- **Then** both candidates are accepted into the seed batch, the schema document records the bounds as inclusive on both endpoints, and the curator's batch log records each entry's observed `description` length value

### AC-5: Each published seed entry records source attribution naming the originating contributor handle

- **Given** a candidate seed entry that has passed the 4 schema-conformance rules
- **When** the curator finalizes the entry for publication
- **Then** the entry's metadata block includes a non-empty `source_contributor` field whose value is a GitHub handle of length 1 to 39 characters matching the regular expression `^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$`, and the metadata block includes a non-empty `source_artifact_reference` field naming the originating prompt usage location

### AC-6: Seed batch publishes 6 unique entries with no duplicate slug identifiers

- **Given** the 6 candidate seed entries finalized for publication
- **When** the curator runs a uniqueness check across the candidate batch
- **Then** the slug field on every entry is a kebab-case string of length 3 to 64 characters, no slug value appears in 2 or more entries, the 6 slugs collectively form a set of cardinality 6, and the seed batch is published only when the cardinality check holds

## Sub-Tasks

Sub-Tasks are owned by individuals (use `@assignee` placeholders); the Story itself is owned by the Team.

- [ ] Identify 6 candidate prompts from existing contributor usage with permission to publish under the catalog license terms @assignee
- [ ] Author each entry's metadata block populating all 8 required fields against the schema at [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) @assignee
- [ ] Author each entry's prompt body with a length between 50 and 500 characters in the `description` field and a complete prompt body in the `prompt_text` field @assignee
- [ ] Run the schema's 4 conformance rules against each candidate entry and reject any non-conformant candidate with a logged reason @assignee
- [ ] Run the slug uniqueness check across the 6 finalized entries and confirm the set cardinality equals 6 @assignee
- [ ] Publish the 6 entries as Markdown files alongside the parent Feature [`../FEATURE-001-02-publish-initial-seed-catalog-entries.md`](../FEATURE-001-02-publish-initial-seed-catalog-entries.md) and verify all relative-path cross-links render on github.com @assignee

## Edge Cases

This Story documents 4 edge cases covering all 4 categories — Empty/Null Input, Boundary Values, and Invalid Input are mandatory; Concurrent/Conflicting Operations is included because multiple curators may submit candidate seed entries within the same authoring window, making slug-collision a true concurrency concern handled by AC-6.

| Category | Required? | Scenario |
|---|---|---|
| Empty/Null Input | Mandatory | A candidate seed entry submits with all 8 metadata fields populated as empty strings — the schema's Required Field Presence Rule rejects the candidate per AC-1 because the empty string violates the schema's per-field minimum-length constraint of 1 character |
| Boundary Values | Mandatory | A candidate seed entry submits with a `description` field of exactly 50 characters and a second candidate submits with exactly 500 characters (the inclusive minimum and maximum) — both candidates pass the Length Constraint Rule per AC-4 |
| Invalid Input | Mandatory | A candidate seed entry submits with the `tags` field encoded as a comma-separated string instead of `array<string>` — the schema's Data-Type Conformance Rule rejects the candidate, the rejection log names the offending field with its declared and observed types, and the candidate is excluded from the 6-entry target |
| Concurrent/Conflicting Operations | Applicable | Two curators independently propose candidate seed entries with the same slug value (e.g., both choose `code-review-helper`) within the same authoring window — the slug uniqueness check defined in AC-6 detects the collision, and the curator who finalized the slug second renames the entry to a slug not present in the existing batch before publication |

### Detailed Edge Case Scenarios

**Edge Case 1: Empty/Null Input — Candidate entry with empty-string field values**

The curator encounters a candidate seed entry where all 8 required metadata fields are present in the entry block but each field's value is the empty string. The schema's Required Field Presence Rule treats the empty string as a length-1 violation against the per-field minimum-length constraint (1 character for `string` fields, 1 element for `array<string>` fields, 50 characters for the `description` field). The candidate is rejected, the curator's rejection log lists every empty field by name, and the candidate is excluded from the 6-entry target per AC-1.

**Edge Case 2: Boundary Values — Candidate entries at the inclusive description-length boundaries**

The curator authors 1 candidate seed entry whose `description` field is exactly 50 characters (the schema's inclusive minimum) and a second whose `description` field is exactly 500 characters (the schema's inclusive maximum). The schema's Length Constraint Rule accepts both candidates because the 50–500 character range is declared as inclusive on both endpoints. The curator's batch log records each entry's observed `description` length, and both candidates count toward the 6-entry seed-batch target per AC-4.

**Edge Case 3: Invalid Input — Candidate entry with type-mismatched tags field**

The curator encounters a candidate seed entry whose `tags` field is encoded as the comma-separated string `"foo,bar,baz"` instead of the schema-declared `array<string>` data type. The schema's Data-Type Conformance Rule rejects the candidate, the rejection log names `tags` with declared type `array<string>` and observed type `string`, and the candidate is returned to the source contributor with a 2-business-day request to re-encode the field as a 3-element array. The candidate does not count toward the 6-entry seed-batch target per AC-1.

**Edge Case 4: Concurrent/Conflicting Operations — Slug-collision between curators**

Two curators independently propose candidate seed entries with the same slug value `code-review-helper` within the same 24-hour authoring window. When the second curator finalizes the candidate, the slug uniqueness check defined in AC-6 detects that the slug already appears in the in-progress batch list and rejects the second candidate's slug. The second curator renames the entry to a slug not present in the existing batch (for example, `code-review-helper-pr` or `code-review-pr-helper`) before publication, the slug uniqueness check then succeeds, and the 6-entry batch is published with 6 distinct slugs forming a set of cardinality 6.

## Dependencies

- Parent Feature: [`FEATURE-001-02`](../FEATURE-001-02-publish-initial-seed-catalog-entries.md)
- Prerequisite Story (schema reference): [`STORY-001-01-01`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) — the 8-field metadata schema that every seed entry must conform to
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Roadmap section, line 134) — the originating roadmap item "Prompt catalog with examples and snippets"
- External Reference: [`../../../profile/README.md`](../../../profile/README.md) (Contributing section, lines 96–106) — the established 11-step contributing workflow that catalog submissions follow
- Downstream Consumer (informational): [`./STORY-001-02-02-verify-seed-entry-metadata.md`](./STORY-001-02-02-verify-seed-entry-metadata.md) — the verification Story that tests the 6 seed entries authored by this Story against the schema
- Shared Schema: the 8-field metadata schema published by `STORY-001-01-01` is referenced normatively in AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, and all 4 edge cases

## Story Estimation Guidance

### Effort

Medium — Authoring 6 catalog entries × 8 metadata fields = 48 metadata values plus 6 prompt bodies and source attributions. Estimated 2–3 days of focused authoring including review and rejection-loop iterations on candidates that fail the schema's 4 conformance rules.

### Complexity

Medium — Each entry must satisfy 4 schema rules (Required Field Presence, Data-Type Conformance, Length Constraint, Worked-Example Format), source attributions must be obtained for every prompt with publication permission, and the 6-entry slug-uniqueness invariant must be enforced across the batch. Concurrency between multiple curators introduces an additional coordination concern handled in Edge Case 4.

### Uncertainty

Low — The 8-field schema is fully specified by [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) before this story starts, and the candidate prompts are sourced from existing contributor usage so no novel content authoring is required. The primary unknown is which 6 prompts will be selected from the larger candidate pool, but selection is a curatorial choice with no technical risk.

### Story Points

Suggested points: **5** from the Fibonacci set `{1, 2, 3, 5, 8, 13}`. Rationale: Medium effort × Medium complexity × Low uncertainty places this story above the 2-point sizing of single-document Stories like [`../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md`](../FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md) and [`./STORY-001-02-02-verify-seed-entry-metadata.md`](./STORY-001-02-02-verify-seed-entry-metadata.md), and above the 3-point sizing of [`../FEATURE-001-01/STORY-001-01-02-define-contribution-review-workflow.md`](../FEATURE-001-01/STORY-001-01-02-define-contribution-review-workflow.md). The 5-point sizing reflects that this story produces 6 distinct deliverables (one per seed entry) rather than a single document, each requiring full schema conformance and source attribution. It is smaller than an 8-point story because the schema is already defined, the prompts are sourced from existing usage, and uncertainty is low.

## Definition of Done (Story-Level)

- [ ] Code passes linting/static analysis
- [ ] Unit tests cover new functionality (minimum 80% coverage)
- [ ] Integration tests validate acceptance criteria (all Given/When/Then scenarios pass)
- [ ] Documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
- [ ] Story is demo-able to Product Owner
