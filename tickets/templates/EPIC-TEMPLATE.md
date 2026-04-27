<!--
EPIC TEMPLATE — Reusable Markdown scaffold for a parent Epic file.

USAGE:
- Do NOT edit this template in place. Copy this file to `tickets/EPIC-NUM-slug.md`
  (the `tickets/` root, alongside `profile/`) and fill every placeholder.
- Replace every `[...]` placeholder text and remove every `<!-- TODO: ... -->`
  HTML comment before committing.
- Epics decompose into 1–3 child Features. Two example Feature links are shown
  in the Features Index below; add a third only if the decomposition warrants it.

NAMING (Rule S-2):
- Filename: `EPIC-NUM-slug.md`
  - `NUM` = zero-padded 3-digit integer (e.g., `EPIC-001`, `EPIC-027`).
  - `slug` = lowercase, hyphen-separated words derived from the Epic Title.
- Co-located subdirectory: `tickets/EPIC-NUM/` (must match this Epic file's
  identifier exactly, e.g., `EPIC-001-foo.md` ↔ `tickets/EPIC-001/`).
- The subdirectory holds child Feature files and their own subdirectories.

TITLE PATTERN (Rule S-7):
- "Action + Object + Outcome", ≤ 255 characters.

CONTENTS (Rule S-4 — exactly 4 H2 sections, in this order):
1. Epic Summary — 2–3 sentence description with business value AND scope
   boundaries (what is included AND what is excluded).
2. Features Index — bulleted list of relative links to 1–3 child Feature
   files at `./EPIC-NUM/FEATURE-NUM-NN-slug.md`.
3. Dependencies — upstream Epics, external systems, or contextual references.
4. Definition of Done (Epic-Level) — VERBATIM 5-item GFM task list (below).

DO NOT MODIFY (Rule P-3 / O-4):
The 5-item Definition of Done (Epic-Level) checklist below is reproduced
character-for-character from the User Story Analyst prompt. Do NOT paraphrase,
reorder, or remove items.

REFERENCES:
- Framework overview: ../README.md
- Canonical analyst prompt: ../USER-STORY-ANALYST.md
- Reference example: ../EPIC-001-establish-prompt-catalog.md
-->

# [Epic Title: Action + Object + Outcome, ≤ 255 chars]

## Epic Summary

<!-- TODO: 2–3 sentence description with business value and scope boundaries -->

[2–3 sentence description capturing the business value this Epic delivers, the user/customer outcome, and an explicit statement of scope boundaries (what is included and what is excluded).]

## Features Index

Bulleted list of relative links to each child Feature file. Each Epic has 1–3 features.

- [`FEATURE-NUM-01` — Feature Title](./EPIC-NUM/FEATURE-NUM-01-slug.md)
- [`FEATURE-NUM-02` — Feature Title](./EPIC-NUM/FEATURE-NUM-02-slug.md)

Add a third entry (`FEATURE-NUM-03`) only if the decomposition warrants it; the upper bound is 3.

## Dependencies

- Upstream Epic dependencies: [list any prior Epics that must complete before this one, with relative paths — e.g., `[EPIC-NUM-OTHER](./EPIC-NUM-OTHER-slug.md)`]
- External system references: [list any external systems, APIs, or services this Epic depends on]
- Roadmap or contextual references: [list any roadmap items, design documents, or strategic references this Epic realizes]

## Definition of Done (Epic-Level)

- [ ] All child features completed
- [ ] Integration testing across features passed
- [ ] Documentation updated
- [ ] Final code review approved
- [ ] CI pipeline passes
