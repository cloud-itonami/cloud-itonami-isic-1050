# Contributing

Thank you for your interest in contributing to cloud-itonami-isic-1050!

## Development Setup

1. Clone this repository
2. Run `clojure -M:test` to verify your environment
3. Code in `.cljc` (portable Clojure, runs on JVM and JavaScript)

## Scope Boundaries

Before contributing, please understand the scope:

**In Scope:**
- Governor checks (food-safety, compliance rules)
- Registry validators (physical/chemical limits)
- Store abstractions (batch state)
- Advisor improvements (proposal logic, mock tests)
- Documentation (ADRs, governance, test cases)

**Out of Scope:**
- Direct equipment control (pasteurizer, cooler operation)
- Regulatory interpretation (only cite published specs)
- Food-safety authority (certification authority remains human)

## Guidelines

1. **All tests must pass**: Run `clojure -M:test` before submitting
2. **Lint checks must pass**: Run `clojure -M:lint` before submitting
3. **.cljc only**: No JVM-only (`:clj`) constructs; must be portable
4. **Governor rules are additive**: Never remove a food-safety check
5. **Audit trail is immutable**: Append-only facts; no history rewriting
6. **Food-safety decisions require human**: Proposals are `effect :propose` only

## Pull Request Process

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit with clear messages citing any specs or ADRs
4. Run `clojure -M:test && clojure -M:lint`
5. Open a pull request with:
   - Description of the change
   - Rationale (why this change improves the actor)
   - Any regulatory citations (if applicable)
   - Test coverage

## Code Style

- Follow the existing code style (see `src/` and `test/`)
- Use clear, domain-specific names (`:contamination-flag-raised?`, not `:flag`)
- Document Governor rules with their food-safety rationale
- Write tests for every Governor check

## Reporting Issues

- Use GitHub Issues for bugs, feature requests, questions
- For security issues, see `SECURITY.md`

## License

By contributing, you agree that your contributions will be licensed under AGPL-3.0-or-later.

---

Questions? Open an issue or discussion thread. Thank you!
