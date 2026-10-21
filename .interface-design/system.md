# Cauce — interface system

Decisions already made. This file anchors any design skill (`interface-design`
reads it as settled direction): **apply it, do not re-explore it.** Domain
exploration, new palettes, new typefaces and "signature" proposals are out of
scope; the brand is the brief.

**Scope:** `frontend/playground` (React 19 + Vite 7 + Tailwind 4 CSS-first).
The Angular dashboard will inherit the same brand when it exists.

## Source of truth

- Tokens live in the `@theme` block of
  `frontend/playground/src/styles/tokens.css`. That block IS the Tailwind
  config. It starts with `--color-*: initial`: Tailwind's default palette does
  not exist here, by design.
- Never hardcode a palette hex in a component or a new stylesheet. Read the
  custom property (`var(--color-teal-500)`) or the utility built from it.
- A new token is a change to `tokens.css`, proposed and justified — never a
  local literal.
- The token names (`surface`, `surface-2`, `stone-800`…) are kept as they are.
  Renaming them for evocativeness is not a goal.
- The static mockups under `frontend/playground/referencias/` (desktop,
  tablet, mobile) are the visual specification. When a screen has a mockup,
  match it.

## Palette

Dark only. There is no light theme and none is to be added.

| Role | Tokens | Use |
|---|---|---|
| Ground | `stone-950` (`bg`), `stone-900` (`surface`), `stone-800` (`surface-2`) | Page, panels, nested panels |
| Rules | `border`, `border-soft` | `border-soft` only inside an already-bordered surface |
| Text | `stone-100` (`text`), `text-2`, `stone-400` (`subtle`), `stone-600` (`muted`) | Primary → quietest |
| Teal 300–600 | Brand accent | Primary action, focus ring, selection, live/active state |
| Moss 400–500 | Positive outcome | A check that passed, a link that holds |
| Slate 400–500 | Information, limits | What a check does not cover, secondary metadata |
| `danger` `#C8664B` | Error / danger (semantic, not brand) | See below |

- Teal is the only brand accent; it is not a status colour.
- **Neutral states are neither moss nor danger.** "Not run yet",
  "recomputing", "unknown" use the stone/subtle tones — no good-or-bad
  colouring of a state that carries no outcome.

### Error and danger

The brand palette has no red; the interface still needs one unmistakable
signal for "this failed" or "this destroys something". That signal is the
semantic token `--color-danger: #C8664B` (a muted rust), plus a tint for
backgrounds, `--color-danger-bg` (`rgba(200, 102, 75, 0.12)`). Both live in
`tokens.css` with the other colours.

- **It is semantic only.** Never decorative, never a second accent, never in
  gradients, illustrations or the logo.
- **Where it applies:** a chain verification that fails (the chain-invalid
  verdict and the broken link it points at), a revoked API key and the
  revoke action, and a failed invocation. Anything else that wants it needs a
  decision recorded here first.
- **Never the only signal.** Every danger state carries an icon *and* a text
  label, e.g. ✕ + "chain invalid", "revoked", "failed". Colour reinforces;
  it never carries the meaning on its own (colour-blind users, greyscale,
  screenshots in reports).
- **Why this colour, measured** (OKLab ΔE ×100, normal / deuteranopia /
  protanopia / tritanopia): against teal-500 30/18/27/35; against moss-400
  21/8/16/25. Contrast on `stone-950` is 5.0:1 (passes AA for text). Slate
  scores only 11/5/8/9 against teal — near-identical
  for deuteranopes — so slate does not signal failure. The low deuteranopia
  figure against moss (8) is inherent to any red against any green; it is
  why the icon-and-label rule is mandatory, not optional.
- **On a light surface** the rust drops to 3.3:1 on `stone-100` and fails AA.
  Any light surface (a light section, or a light theme of the dashboard) uses
  `--color-danger-on-light: #A0523C` — the same hue, darkened: 4.8:1 on
  `stone-100`, 5.6:1 on white. The playground's `tokens.css` does not define
  it, because the playground has no light surface; it is added the day one
  exists, scoped to that surface.

## Typography

| Family | Token | Role |
|---|---|---|
| Fraunces (variable: `opsz`, `SOFT`, `WONK`) | `--font-display` | Headings, view titles, large figures |
| Geist 300–600 | `--font-body` | Body and UI copy; base 14px / 1.65 |
| JetBrains Mono 400–500 | `--font-mono` | Ids, hashes, keys, code, machine values |

- Fraunces is set at weight 600 with `font-variation-settings` per role
  (`"opsz"` 72–144, `"SOFT" 0`, `"WONK" 1`) and negative tracking at large
  sizes. Follow the existing roles in `audit.css` / `primitives.css`.
- Figures that compare or count get `font-variant-numeric: tabular-nums`.
- Monospace is for values a developer would copy, not for decoration.
- No other families; fonts load once in `index.html` (Google Fonts).

## Shape, layout and motion

- Radii: `--radius-sm` 8, `--radius-md` 12, `--radius-lg` 18, `--radius-full`.
- Layout constants: `--sidebar-w` 248, `--rail-w` 68, `--topbar-h` 58 (plus
  the safe-area inset below 700px). Breakpoints 700 / 1024px are literals in
  `shell.css` / `session.css`.
- Motion: `--ease`, `--t-fast` 0.14s, `--t-mid` 0.24s. Motion that encodes
  state is allowed; decoration is minimal. The global
  `prefers-reduced-motion` switch in `tokens.css` is the policy — do not
  re-implement it per component.
- Focus: the global `:focus-visible` teal ring. Never remove it without an
  equivalent replacement.
- The film grain overlay (`.grain`) stays inert and near-invisible.

## Building

- Reuse what exists: `src/styles/primitives.css`, the shell components, the
  existing view CSS files, before writing a new class.
- No new UI dependencies (component kits, headless libraries, icon packs)
  without an explicit decision. Native elements, styled with the tokens,
  first.
- `frontend/playground/scripts/check-css-scope.mjs` guards CSS scoping; the
  build (`npm run build`) must pass.

## Vocabulary

Cauce shows **verifiable technical capability, never a legal verdict.**

- Say what the system computed or observed: "chain verified up to entry
  N", "hash mismatch at entry N", "signature checks against key K",
  "entries verified".
- Never state a conclusion about rules, obligations or proof value: no
  claim that something is compliant, certified, guaranteed, admissible,
  legally valid, or tamper-proof. Whether a fact satisfies an obligation is
  the customer's judgment, not the interface's.
- A limit is stated as a limit ("detects", "does not detect"), not softened
  into reassurance.
- On the audit screen and its wire types this is enforced by
  `frontend/playground/scripts/check-vocabulary.mjs` (it owns the word list);
  everywhere else it is the same rule applied by judgment.

## Copy and localisation

- The interface language is English: sentence case ("New agent", "Audit
  chain"), active voice, second person, curly quotes “ ”.
- **No user-facing string is hardcoded in a component.** Every label,
  heading, message, placeholder, `aria-label` and error text lives in a
  per-locale message catalogue under `src/i18n/` (`en` is the only locale);
  components reference keys. Adding Spanish must be adding one catalogue
  file, with no component edits.
- Not copy, and stays literal: ids, hashes, key prefixes, enum values from
  the API (`OPEN`, `FAILED`…) and other wire data rendered as data.
- The vocabulary guard must cover the catalogue: when copy for the audit
  screen moves into `src/i18n/`, `check-vocabulary.mjs` gains that path, or
  the rule silently stops being enforced.
