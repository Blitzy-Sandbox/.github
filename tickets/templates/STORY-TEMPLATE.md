<!--
STORY TEMPLATE — Reusable Markdown scaffold for an individual user Story file.

USAGE:
- Do NOT edit this template in place. Copy this file to
  `tickets/EPIC-NUM/FEATURE-NUM-NN/STORY-NUM-NN-SS-slug.md` (inside the parent
  Feature's co-located subdirectory) and fill every placeholder.
- Replace every `[...]` placeholder text and delete every TODO authoring
  HTML comment before committing.
- Each Story is owned by the Team. Sub-Tasks within a Story are owned by
  individuals (use `@assignee` placeholders).

NAMING (Rule S-2):
- Filename: `STORY-NUM-NN-SS-slug.md`
  - `NUM` = zero-padded 3-digit integer matching the parent Epic's id
    (e.g., `STORY-001-NN-SS` for Epic `EPIC-001`).
  - `NN` = zero-padded 2-digit Feature sequence number matching the parent
    Feature's id (e.g., `01`, `02`, `03`).
  - `SS` = zero-padded 2-digit Story sequence number within the Feature
    (e.g., `01`, `02`, `03`, `04`, `05`).
  - `slug` = lowercase, hyphen-separated words derived from the Story Title.
- File location: `tickets/EPIC-NUM/FEATURE-NUM-NN/STORY-NUM-NN-SS-slug.md`.
  Stories do NOT have a co-located subdirectory — Sub-Tasks are kept inline
  within the Story file.

TITLE PATTERN (Rule S-7):
- "Action + Object + Outcome", ≤ 255 characters.

PARENT BACK-LINK (Rule T-3):
- Always include the parent Feature back-link in Dependencies as the FIRST item:
  `Parent Feature: [\`FEATURE-NUM-NN\`](../FEATURE-NUM-NN-slug.md)`
- The `../` prefix ascends one directory from
  `tickets/EPIC-NUM/FEATURE-NUM-NN/` back to `tickets/EPIC-NUM/` where the
  parent Feature file lives.

CONTENTS (Rule S-6 — exactly 7 H2 sections, in this order):
1. User Story — WHO/WHAT/WHY blockquote with concrete named role; embedded
   INVEST Validation (H3) and Demo-able (H3) sub-sections.
2. Acceptance Criteria — 4 to 8 BDD-formatted criteria; exactly one trigger
   per `When` clause; required coverage across input validation, valid
   output, error handling, and edge case handling.
3. Sub-Tasks — 3 to N actionable task-list rows ending with `@assignee`.
4. Edge Cases — 4-row mandatory category table + Detailed Edge Case
   Scenarios (H3) sub-section with 3 to 5 narrative blocks.
5. Dependencies — Parent Feature back-link, prerequisite Stories, external
   references, shared schemas.
6. Story Estimation Guidance — Effort (H3), Complexity (H3), Uncertainty
   (H3), Story Points (H3) drawn from `{1, 2, 3, 5, 8, 13}`.
7. Definition of Done (Story-Level) — VERBATIM 7-item GFM task list (below).

INVEST (Rule C-2 — all 6 criteria must be satisfied per Story):
- Independent — self-contained with no inherent dependency on another user story
- Negotiable — can be changed or rewritten until committed to an iteration
- Valuable — delivers measurable value to the end user and/or customer
- Estimable — size can be estimated with a certain level of certainty
- Sized appropriately — small enough to plan, task, and prioritize with certainty (completable within a sprint)
- Testable — provides necessary information to make test development possible

BDD ACCEPTANCE CRITERIA (Rules C-4 through C-7):
- Each AC formatted as: `**AC-N: Title**` line, then bulleted `**Given**`,
  `**When**`, `**Then**` lines.
- Single trigger per `When` clause (Rule C-6); compound actions split across
  multiple ACs.
- Required coverage (Rule C-7): at least one AC for each of:
    * Input validation (invalid input scenario)
    * Expected output/behavior (valid input scenario)
    * Error handling (error condition scenario)
    * Edge case handling (boundary condition scenario)
- Total AC count: 4 minimum, 8 maximum.

FORBIDDEN TERMS (Rule L-1 — must NOT appear in any acceptance criterion;
case-insensitive match against the 13 terms below):
    approximately, several, various, adequate, appropriate, properly,
    correctly, efficiently, quickly, easily, user-friendly, reasonable,
    sufficient

Every time-, count-, volume-, or quality-related claim must be expressed
with a concrete measurable value (e.g., "within 2 seconds", "at least 3
entries", "maximum 500 characters") rather than a vague qualifier.

EDGE CASES (Rule C-8 — 3 to 5 documented scenarios per Story):
- Empty/Null Input — Mandatory
- Boundary Values — Mandatory
- Invalid Input — Mandatory
- Concurrent/Conflicting Operations — If applicable

STORY ESTIMATION (Rule C-9):
- Effort (Low/Medium/High) with rationale.
- Complexity (Low/Medium/High) with rationale.
- Uncertainty (Low/Medium/High) with rationale.
- Story Points drawn from the Fibonacci set `{1, 2, 3, 5, 8, 13}` with
  relative-sizing rationale.

DEMO-ABILITY (Rule C-3):
- Every Story is demonstrable to a Product Owner or Business Owner. State
  the concrete demo flow under the Demo-able sub-section.

DO NOT MODIFY (Rule P-3 / O-6):
The 7-item Definition of Done (Story-Level) checklist below is reproduced
character-for-character from the User Story Analyst prompt. Do NOT
paraphrase, reorder, or remove items.

REFERENCES:
- Framework overview: ../../README.md
- Canonical analyst prompt: ../../USER-STORY-ANALYST.md
- Sibling Epic template: ./EPIC-TEMPLATE.md
- Sibling Feature template: ./FEATURE-TEMPLATE.md
- Reference example Story: ../../EPIC-001/FEATURE-001-01/STORY-001-01-01-document-prompt-metadata-schema.md
-->

# [Story Title: Action + Object + Outcome, ≤ 255 chars]

## User Story

<!-- TODO: Replace the blockquote below with a concrete WHO/WHAT/WHY triplet.
     The role MUST be a concrete named role (e.g., "Sandbox Content Curator",
     "API Consumer", "Site Reliability Engineer"). Generic labels such as
     "user" or "someone" are prohibited. -->

> As a [specific named user or role],
> I want [specific goal or capability],
> So that [measurable business or user outcome].

The role above must be a concrete named role — generic terms such as "user" or "someone" are not accepted. The WHY clause must describe a measurable business or user outcome (e.g., "reduce onboarding time from 8 hours to under 2 hours") rather than a vague benefit.

### INVEST Validation

This story satisfies all six INVEST criteria:

- [ ] **Independent** — [rationale: why this story has no inherent dependency on another user story]
- [ ] **Negotiable** — [rationale: scope is open to change or rewrite until committed to an iteration]
- [ ] **Valuable** — [rationale: measurable user/business value delivered by this story]
- [ ] **Estimable** — [rationale: scope is concrete enough to estimate with a certain level of certainty]
- [ ] **Sized appropriately** — [rationale: completable within a single sprint]
- [ ] **Testable** — [rationale: acceptance criteria support verifiable test development]

### Demo-able

This story is demonstrable to the Product Owner / Business Owner for acceptance.

Demo scenario: [TODO: Describe the concrete demo flow that a Product Owner can witness to confirm acceptance — list the trigger, the observable outcome, and the data state required for the demo.]

## Acceptance Criteria

Each Story has between 4 and 8 acceptance criteria inclusive (Rule C-4). Each criterion uses the **AC-N: Descriptive Title** format (Rule C-5). Each `When` clause contains a single trigger (Rule C-6); compound actions are split across multiple ACs. Required coverage (Rule C-7) mandates at least one AC for each of: input validation, expected output/behavior, error handling, and edge case handling. The 13 forbidden terms (`approximately`, `several`, `various`, `adequate`, `appropriate`, `properly`, `correctly`, `efficiently`, `quickly`, `easily`, `user-friendly`, `reasonable`, `sufficient`) must NOT appear in any criterion (Rule L-1). Every numeric or quality claim must be expressed as a concrete measurable value.

<!-- TODO: Replace each AC-N placeholder with a concrete acceptance criterion.
     Keep the bold heading line, then the three bulleted Given/When/Then lines.
     Add up to 4 more ACs (AC-5 through AC-8) following the same format; the
     total must remain between 4 and 8 inclusive. -->

**AC-1: [Descriptive Title — covers Input Validation]**
- **Given** [a specific invalid-input scenario or precondition, including the concrete invalid value]
- **When** [a single action or trigger]
- **Then** [the expected validation result, rejection outcome, or error message — stated as a concrete observable]

**AC-2: [Descriptive Title — covers Expected Output/Behavior]**
- **Given** [a specific valid-input scenario or precondition, including the concrete valid value]
- **When** [a single action or trigger]
- **Then** [the expected concrete, measurable output or behavior — include numeric thresholds, time bounds, or counts]

**AC-3: [Descriptive Title — covers Error Handling]**
- **Given** [a specific error-condition scenario or precondition (e.g., dependency unavailable, network failure, downstream timeout)]
- **When** [a single action or trigger]
- **Then** [the expected error response, error message text, recovery behavior, or retry semantics — stated as a concrete observable]

**AC-4: [Descriptive Title — covers Edge Case Handling]**
- **Given** [a specific boundary-condition scenario or precondition (e.g., minimum/maximum length, lowest/highest numeric value)]
- **When** [a single action or trigger]
- **Then** [the expected boundary-handling outcome — stated as a concrete observable]

<!-- Add AC-5, AC-6, AC-7, AC-8 below as needed. The total number of
     acceptance criteria must remain between 4 and 8 inclusive. Each
     additional AC follows the same `**AC-N: Title**` + Given/When/Then
     format. -->

## Sub-Tasks

Sub-Tasks are owned by individuals (use `@assignee` placeholders); the Story itself is owned by the Team. Sub-Tasks describe the actionable work required to turn the Story into an increment.

- [ ] [Action verb + concrete deliverable] @assignee
- [ ] [Action verb + concrete deliverable] @assignee
- [ ] [Action verb + concrete deliverable] @assignee

Each sub-task should begin with an action verb (e.g., Implement, Document, Verify, Configure, Refactor, Test) and end with an `@assignee` placeholder that the team replaces with a concrete handle when committing to the sprint. Add additional sub-task rows as the decomposition warrants.

## Edge Cases

Document 3 to 5 edge cases per Story (Rule C-8). The first three categories below are MANDATORY in every Story; the fourth category is included only if the Story's behavior involves shared mutable state, concurrent access, or conflict resolution. If Concurrent/Conflicting Operations is not applicable, remove that table row entirely (do not leave it blank).

| Category | Required? | Scenario |
|---|---|---|
| Empty/Null Input | Mandatory | [TODO: Describe the empty/null input scenario and expected handling — include the empty value (e.g., empty string, null reference, zero-element collection) and the concrete expected response] |
| Boundary Values | Mandatory | [TODO: Describe the minimum/maximum boundary scenario and expected handling — include the concrete boundary value and the expected outcome at that boundary] |
| Invalid Input | Mandatory | [TODO: Describe a specific invalid-input scenario and expected handling — include the concrete invalid value (malformed type, out-of-range value, unauthorized format) and the expected rejection or error response] |
| Concurrent/Conflicting Operations | If applicable | [TODO: Describe a concurrency/conflict scenario (e.g., simultaneous writes, race conditions, lock contention) and expected handling — OR remove this row if N/A] |

### Detailed Edge Case Scenarios

Provide a narrative description for each edge case listed above. Each detailed scenario complements the table by capturing the input/state setup, the trigger, and the expected handling in prose.

**Edge Case 1: [Empty/Null Input — Title]**

[Detailed description of the scenario: what input or state triggers the edge case, what the system is expected to do in response, and any observable side effects or error conditions to verify during testing.]

**Edge Case 2: [Boundary Values — Title]**

[Detailed description of the scenario: the concrete boundary value(s) under test, the trigger event, and the expected behavior at the boundary.]

**Edge Case 3: [Invalid Input — Title]**

[Detailed description of the scenario: the concrete invalid value, the trigger event, and the expected rejection, error message, or recovery behavior.]

<!-- Add Edge Case 4 (Concurrent/Conflicting Operations) and Edge Case 5
     (additional category, if needed) below following the same format. The
     total number of detailed edge case scenarios must remain between 3
     and 5 inclusive and must align with the rows present in the table
     above. -->

## Dependencies

List every prerequisite that may affect estimation, sequencing, or testability.

- Parent Feature: [`FEATURE-NUM-NN`](../FEATURE-NUM-NN-slug.md)
- Prerequisite Stories: [list any sibling Story files that must complete before this one, with relative paths — e.g., `[STORY-NUM-NN-OTHER](./STORY-NUM-NN-OTHER-slug.md)`]
- External References: [list any external services, APIs, or documents this Story depends on]
- Shared Schemas: [list any cross-feature schemas, data models, or contracts referenced by this Story]

## Story Estimation Guidance

Provide each rubric value (Effort, Complexity, Uncertainty) with rationale, then map to a Fibonacci story-point value.

### Effort

[Low / Medium / High] — [rationale: estimated hours/days of focused work required to complete this Story]

### Complexity

[Low / Medium / High] — [rationale: technical or domain complexity factors — algorithmic depth, architectural impact, integration breadth]

### Uncertainty

[Low / Medium / High] — [rationale: unknowns, dependencies, or risk factors — third-party APIs, undocumented behavior, novel domain]

### Story Points

Suggested points: [1 / 2 / 3 / 5 / 8 / 13] from the Fibonacci set `{1, 2, 3, 5, 8, 13}` — [rationale: how this size compares to other stories in the team's backlog (e.g., "comparable in size to STORY-NNN-NN-NN, which the team completed in one sprint")]

Story points represent **relative size**, not absolute hours. Stories larger than 13 points must be split into two or more Stories until each part fits within the scale.

## Definition of Done (Story-Level)

- [ ] Code passes linting/static analysis
- [ ] Unit tests cover new functionality (minimum 80% coverage)
- [ ] Integration tests validate acceptance criteria (all Given/When/Then scenarios pass)
- [ ] Documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
- [ ] Story is demo-able to Product Owner
