# Policy: ADR Triggers and Decision Conventions

Structural changes must be recorded as Architectural Decision Records (ADRs) rather than informal code edits.

## The Rule

- **ADR Required When**:
  - Changing the host-runtime ownership model or request ingress path.
  - Modifying mode boundaries or semantics (`PURE_MODE` vs `COMPATIBILITY_MODE`).
  - Altering module contracts or adding cross-module dependencies.
  - Adding or redefining persistence, security, or graph bridge seams.
  - Modifying binary neutrality or JDK baseline constraints.
- **Status Conventions in `docs/adr/`**:
  - **`ACCEPTED`** (accepted-on-merge): Used for ADRs with a single decider and clear decision already made at drafting time. Merging the PR ratifies the decision.
  - **`PROPOSED`**: Used only when a multi-stakeholder decision is open and being actively deliberated before commitment.
- **No Silent Amendments**: Do not amend an accepted ADR without either submitting an explicit superseding/amending ADR or clearly moving the ADR back to deliberation.
