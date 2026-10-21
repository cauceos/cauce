---
name: web-design-guidelines
description: Review frontend UI code (the playground) against the vendored Web Interface Guidelines — accessibility, focus, forms, motion, typography, i18n. Use when asked to "review my UI", "check accessibility", "audit design", "review UX", or "check the screen against best practices".
argument-hint: <file-or-pattern>
---

# Web Interface Guidelines (vendored)

Review files for compliance with the Web Interface Guidelines in
[`guidelines.md`](guidelines.md), next to this file.

## Provenance — read before editing

This skill is vendored, not installed: nothing is fetched at run time.

- `SKILL.md`: rewritten from `vercel-labs/agent-skills`
  @ `063bee94c3f4df8453406c830b0a7df0f2860278`
  (`skills/web-design-guidelines/SKILL.md`). The upstream version fetches the
  rules from the `main` branch on every run; this one reads the local copy.
- `guidelines.md`: `vercel-labs/web-interface-guidelines`
  @ `434b7f91364665f2f733b310ec54809bf8f37937` (`command.md`), MIT — see
  [`LICENSE`](LICENSE).
- Local changes to `guidelines.md`: removed the "Title Case for
  headings/buttons" rule (the playground's copy is sentence case — "New
  agent", "Audit chain" — and Spanish copy would be too), and the
  button-label example follows suit.

It is deliberately absent from `skills-lock.json`: `npx skills update` would
overwrite the local changes with upstream `main`. To update, re-fetch both
files at a newer commit, re-apply the local changes, read the diff, and
update the commit hashes above.

## How it works

1. Read `guidelines.md` in this directory. Do not fetch anything.
2. Read the specified files (or ask the user for files/pattern).
3. Check them against every rule in `guidelines.md`.
4. Output findings in the `file:line` format that `guidelines.md` specifies.

## Project precedence

The project's own rules win over any guideline here:

- Visual identity is single-sourced in `frontend/playground/src/styles/tokens.css`
  (and `.interface-design/system.md`); a guideline never justifies a new
  colour, font or palette.
- The playground is dark-only; "Dark Mode & Theming" rules apply to that one
  theme, never to adding a light one.
- Rules about SSR/hydration do not apply (Vite SPA, no server rendering).
- Copy on the audit screen states a verifiable technical capability, never a
  legal verdict; `scripts/check-vocabulary.mjs` is the guard.

If no files are specified, ask the user which files to review.
