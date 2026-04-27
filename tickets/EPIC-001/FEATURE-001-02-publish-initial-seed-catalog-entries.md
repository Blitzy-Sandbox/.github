# Publish initial seed set of catalog entries

## Feature Summary

This feature populates the prompt catalog with six initial entries sourced from existing contributor usage, demonstrating the published schema and giving downstream contributors concrete reference examples to learn from. It directly advances [`EPIC-001`](../EPIC-001-establish-prompt-catalog.md) by transitioning the catalog from "schema only" to "schema + reference content," ensuring the catalog has discoverable value at launch.

## User Stories Index

- [`STORY-001-02-01` — Document six seed prompts sourced from existing contributor usage](./FEATURE-001-02/STORY-001-02-01-document-six-seed-prompts.md)
- [`STORY-001-02-02` — Verify seed-entry metadata against the published schema](./FEATURE-001-02/STORY-001-02-02-verify-seed-entry-metadata.md)

## Dependencies

- Parent Epic: [`EPIC-001`](../EPIC-001-establish-prompt-catalog.md)
- Sibling Feature prerequisite: [`FEATURE-001-01`](./FEATURE-001-01-define-catalog-schema-and-contribution-guidelines.md) — the catalog schema must be defined before seed entries can be authored against it.
- External references: roadmap context in [`../../profile/README.md`](../../profile/README.md) (Roadmap section, line 134); contribution workflow context in [`../../profile/README.md`](../../profile/README.md) (Contributing section, lines 96–106).

## Definition of Done (Feature-Level)

- [ ] All child user stories completed
- [ ] Feature-level integration testing passed
- [ ] Feature documentation updated
- [ ] Code reviewed and approved
- [ ] CI pipeline passes
