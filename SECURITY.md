# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do not** open a public GitHub issue
2. Email the maintainers with:
   - Description of the vulnerability
   - Steps to reproduce (if possible)
   - Potential impact
   - Suggested fix (if you have one)

We will acknowledge receipt within 48 hours and work on a fix.

## Security Scope

### Critical Security Issues

- Governor logic bypassed (a proposal commits despite hard violations)
- Audit trail corrupted or rewritten
- False negatives in food-safety checks (contamination not flagged)
- Batch double-processed or double-shipped (idempotency violated)

### Operational Security Issues

- Configuration leaks (secrets in logs)
- Store access control (unauthorized batch read)
- Proposal injection (attacker-controlled values bypass Governor)

### Out of Scope

- DoS attacks against the actor runtime (mitigated at infrastructure layer)
- Side-channel attacks (timing, cache behavior)
- Equipment-control exploits (out of actor scope by design)

## Supported Versions

- Latest release: supported for security fixes
- Older releases: case-by-case (typically 1 version back)

## Remediation Process

1. Vulnerability confirmed and isolated
2. Fix developed and tested
3. Patch released (with CVE if applicable)
4. Advisory published
5. Ecosystem notified

## Code Review & Audits

This actor coordinates **food-safety-critical operations**. We welcome:
- Security code reviews
- Formal audits
- Regulatory feedback
- Penetration testing (contact maintainers first)

---

Thank you for helping keep this actor secure.
