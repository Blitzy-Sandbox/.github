[![Docs][docs-shield]][docs-url]
[![Platform][platform-shield]][platform-url]
[![Site][site-shield]][site-url]

<!-- Project header -->
<div align="center">
  <h1>Blitzy Sandbox</h1>
  <p>Try AI-native development. No setup required.</p>
  <a href="https://docs.blitzy.com"><strong>Explore the docs</strong></a>
  &middot;
  <a href="https://platform.blitzy.com">Open Platform</a>
  &middot;
  <a href="https://blitzy.com">About Blitzy</a>
</div>

<!-- Table of Contents -->
<details>
  <summary>Table of Contents</summary>
  <ol>
    <li><a href="#about-the-project">About The Project</a></li>
    <li><a href="#getting-started">Getting Started</a></li>
    <li><a href="#how-blitzy-works">How Blitzy Works</a></li>
    <li><a href="#project-lifecycle">Project Lifecycle</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#resources">Resources</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

---

## About The Project

This organization hosts public, production-grade codebases for [Blitzy Explore][platform-url] members. Every repo is standalone, pre-configured, and ready to clone.

Sandbox projects are:
- **Standalone** — No connection to your work systems
- **Ready to use** — Pre-configured environments and dependencies
- **Public** — Built for learning and testing

![Repositories][repos-shield]

---

## Getting Started

### New to Explore?

Pick a project. Clone it. Start experimenting.

1. **Browse the [Platform][platform-url]** — Find projects by language or lifecycle stage. Review and download artifacts.
2. **Browse the Sandbox** — Read commits, PRs, and generated code. Clone repos and test them locally.
3. **Experiment with Prompts** — Use the provided templates. Submit prompts through the platform. See what Blitzy generates. Iterate.

### Active Contributors

- **Test Workflows** — Try different prompt strategies
- **Review Outputs** — Compare generated code against the AAP (Agent Action Plan)
- **Share Learnings** — Send discoveries to sandbox@blitzy.com

---

## How Blitzy Works

Blitzy generates **up to 80% of a codebase** with AI agents. The remaining 20% — edge cases, optimizations, production polish — stays with human engineers.

Three things make this possible:

**Deep reasoning.** Blitzy agents spend hours or days on a problem. They don't guess in seconds — they reason, collaborate, and test before delivering code.

**Many agents working together.** Specialized agents handle different parts of your codebase. They validate each other's work through compile-time and runtime checks.

**Tech Spec as foundation.** Every project starts with an auto-generated Technical Specification: codebase docs, dependency maps, architecture diagrams, API contracts, and data models. The spec stays in sync as the project evolves.

---

## Project Lifecycle

Each repository is tagged with its current stage:

| Stage | What happens |
|-------|--------------|
| [![Ingestion][stage-ingestion-shield]][stage-ingestion-url] | Codebase mapping and dependency analysis |
| [![Tech Spec][stage-techspec-shield]][stage-techspec-url] | Auto-generated documentation |
| [![Prompt Review][stage-prompt-shield]][stage-prompt-url] | Requirements refinement and validation |
| [![AAP Generation][stage-aap-shield]][stage-aap-url] | Agent Action Plan creation |
| [![Project Guide Review][stage-review-shield]][stage-review-url] | Human review — the critical 20% |
| [![Code Review][stage-complete-shield]][stage-complete-url] | Production-ready code |

---

## Contributing

1. Create a new project on the [Blitzy platform][platform-url]
2. Under GitHub settings, select **Blitzy-Sandbox** as the organization
3. Choose a repository for your project
4. Create a branch off the main (non-blitzy) branch
5. Start ingestion
6. Select a project type and submit your prompt
7. Review the Agent Action Plan — suggest improvements if needed
8. Once the AAP is approved, submit for code generation
9. Review the project guide, then click through to the GitHub PR
10. Review the pull request and build using the provided developer instructions
11. Submit refinement PRs if needed

---

## Resources

| Resource | Link |
|----------|------|
| Platform Docs | [docs.blitzy.com][docs-url] |
| Live Platform | [platform.blitzy.com][platform-url] |
| Prompt Templates | [View Templates][templates-url] |
| Best Practices | [View Guide][best-practices-url] |
| Tech Spec Guide | [View Guide][stage-techspec-url] |
| AAP Validation | [View Guide][stage-aap-url] |

---

## Contact

- **Discussions** — Share insights, prompts, and questions
- **Sandbox support** — sandbox@blitzy.com
- **Customer support** — support@blitzy.com
- **Company site** — [blitzy.com][site-url]

---

## Roadmap

- [ ] Prompt catalog with examples and snippets
- [ ] Multi-repository orchestration demos
- [ ] Community prompt marketplace

---

## Acknowledgments

Blitzy Sandbox is powered by Explore members pushing the boundaries of AI development, the Blitzy engineering team, and the open source community.

Individual repositories may have different licenses. Check each project's LICENSE file.

<!-- Reference-style links -->
[docs-shield]: https://img.shields.io/badge/Docs-Welcome-blue.svg?style=for-the-badge
[docs-url]: https://docs.blitzy.com/
[platform-shield]: https://img.shields.io/badge/Platform-Explore-green.svg?style=for-the-badge
[platform-url]: https://platform.blitzy.com
[site-shield]: https://img.shields.io/badge/About%20Us-Site-purple.svg?style=for-the-badge
[site-url]: https://blitzy.com
[repos-shield]: https://img.shields.io/badge/dynamic/json?url=https://api.github.com/orgs/blitzy-sandbox&query=$.public_repos&label=Public%20Repositories&color=blue&style=for-the-badge
[templates-url]: https://docs.blitzy.com/templates
[best-practices-url]: https://docs.blitzy.com/prompt-engineering/golden-rules
[stage-ingestion-shield]: https://img.shields.io/badge/Stage-Ingestion-yellow
[stage-ingestion-url]: https://docs.blitzy.com/prompt-engineering/ingestion
[stage-techspec-shield]: https://img.shields.io/badge/Stage-Tech_Spec-blue
[stage-techspec-url]: https://docs.blitzy.com/project-lifecycle/tech-spec-review
[stage-prompt-shield]: https://img.shields.io/badge/Stage-Prompt-orange
[stage-prompt-url]: https://docs.blitzy.com/prompt-engineering/validation-checklist
[stage-aap-shield]: https://img.shields.io/badge/Stage-AAP-purple
[stage-aap-url]: https://docs.blitzy.com/project-lifecycle/aap-review
[stage-review-shield]: https://img.shields.io/badge/Stage-Review-red
[stage-review-url]: https://docs.blitzy.com/project-lifecycle/project-guide-review
[stage-complete-shield]: https://img.shields.io/badge/Stage-Complete-brightgreen
[stage-complete-url]: https://docs.blitzy.com/project-lifecycle/code-review
