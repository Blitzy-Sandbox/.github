<!--
FEATURE TEMPLATE — Reusable Markdown scaffold for a Feature file.

USAGE:
- Do NOT edit this template in place. Copy this file to
  `tickets/EPIC-NUM/FEATURE-NUM-NN-slug.md` (inside the parent Epic's
  co-located subdirectory) and fill every placeholder.
- Replace every `[...]` placeholder text and delete every TODO authoring
  HTML comment before committing.
- Features decompose into 2–5 child Stories. Two example Story links are shown
  in the User Stories Index below; add more (up to 5) as the decomposition
  warrants. Fewer than 2 stories indicates the Feature should be folded into
  a Story; more than 5 indicates the Feature should be split.

NAMING (Rule S-2):
- Filename: `FEATURE-NUM-NN-slug.md`
  - `NUM` = zero-padded 3-digit integer matching the parent Epic's id
    (e.g., `FEATURE-001-NN` for Epic `EPIC-001`).
  - `NN` = zero-padded 2-digit Feature sequence number within the Epic
    (e.g., `01`, `02`, `03`).
  - `slug` = lowercase, hyphen-separated words derived from the Feature Title.
- Co-located subdirectory: `tickets/EPIC-NUM/FEATURE-NUM-NN/` (must match
  this Feature file's identifier exactly, e.g.,
  `FEATURE-001-01-foo.md` ↔ `tickets/EPIC-001/FEATURE-001-01/`).
- The subdirectory holds child Story files.

TITLE PATTERN (Rule S-7):
- "Action + Object + Outcome", ≤ 255 characters.

PARENT BACK-LINK (Rule T-3):
- Always include the parent Epic back-link in Dependencies as the FIRST item:
  `Parent Epic: [\`EPIC-NUM\`](../EPIC-NUM-slug.md)`
- The `../` prefix ascends one directory from `tickets/EPIC-NUM/` back to
  the `tickets/` root where the Epic file lives.

CONTENTS (Rule S-5 — exactly 4 H2 sections, in this order):
1. Feature Summary — 1–2 sentence description with epic contribution
   (how this Feature advances the parent Epic's outcome).
2. User Stories Index — bulleted list of relative links to 2–5 child Story
   files at `./FEATURE-NUM-NN/STORY-NUM-NN-SS-slug.md`.
3. Dependencies — parent Epic back-link (mandatory FIRST item), sibling
   Feature dependencies, external references.
4. Definition of Done (Feature-Level) — VERBATIM 5-item GFM task list (below).

DO NOT MODIFY (Rule P-3 / O-5):
The 5-item Definition of Done (Feature-Level) checklist below is reproduced
character-for-character from the User Story Analyst prompt. Do NOT paraphrase,
reorder, or remove items.

NO H3 SUB-SECTIONS:
Per AAP Section 0.4.3 heading hierarchy, Feature files use only H1 and H2.
Do not introduce H3 sub-sections — they are reserved for Story files
(under `Acceptance Criteria` and `Edge Cases`).

REFERENCES:
- Framework overview: ../README.md
- Canonical analyst prompt: ../USER-STORY-ANALYST.md
- Reference example: ../EPIC-001/FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md
-->

# [Feature Title: Action + Object + Outcome, ≤ 255 chars]

## Feature Summary

<!-- TODO: 1–2 sentence description with epic contribution -->

[1–2 sentence description of this feature, including its specific contribution to the parent Epic's outcome.]

## User Stories Index

Bulleted list of relative links to each child Story file. Each Feature has 2–5 stories.

- [`STORY-NUM-NN-01` — Story Title](./FEATURE-NUM-NN/STORY-NUM-NN-01-slug.md)
- [`STORY-NUM-NN-02` — Story Title](./FEATURE-NUM-NN/STORY-NUM-NN-02-slug.md)

Add up to 3 more entries (`STORY-NUM-NN-03` through `STORY-NUM-NN-05`) as needed; the upper bound is 5.

## Dependencies

- Parent Epic: [`EPIC-NUM`](../EPIC-NUM-slug.md)
- Sibling Feature dependencies: [list any sibling Feature files that must complete before this one, with relative paths — e.g., `[FEATURE-NUM-NN-OTHER](./FEATURE-NUM-NN-OTHER-slug.md)`]
- External references: [list any external services, APIs, or documents this feature depends on]

## Definition of Done (Feature-Level)

- [ ] All child user stories completed
- [ ] Feature-level integration testing passed
- [ ] Feature documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
