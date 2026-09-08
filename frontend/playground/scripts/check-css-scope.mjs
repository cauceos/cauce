/**
 * Guards the one hazard of having a stylesheet per screen: two screens
 * defining the same bare class.
 *
 * The screen stylesheets are all loaded together, so a bare `.foo {}` in
 * two of them is not two local rules — it is one global rule where the
 * last file imported wins, silently, on a screen whose own file says
 * otherwise. That is how the session screen lost its grid to the
 * invocations ledger.
 *
 * A bare selector is exactly `.foo` (or `.foo, .bar`). Anything narrower —
 * `.foo.bar`, `.foo .bar`, `.screen .foo` — is a modifier or a descendant,
 * which is how a screen is meant to extend a shared primitive, so those
 * are left alone. Rules inside media queries count: they collide too.
 *
 * Shared layers (tokens, primitives, shell) are the ones ALLOWED to own
 * bare classes; they are not checked against each other.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const SCREEN_SHEETS = ['session', 'conversation', 'workspace', 'invocations', 'audit']

const BARE_RULE = /^[ \t]*(\.[A-Za-z][\w-]*(?:[ \t]*,[ \t]*\.[A-Za-z][\w-]*)*)[ \t]*\{/gm
const BARE_SELECTOR = /^\.[A-Za-z][\w-]*$/

/** Every bare class a sheet defines, with the line it defines it on. */
function bareClasses(css) {
  const found = new Map()
  for (const match of css.matchAll(BARE_RULE)) {
    for (const selector of match[1].split(',')) {
      const name = selector.trim()
      if (!BARE_SELECTOR.test(name)) continue
      if (!found.has(name)) found.set(name, css.slice(0, match.index).split('\n').length)
    }
  }
  return found
}

const owners = new Map()
for (const sheet of SCREEN_SHEETS) {
  let css
  try {
    css = readFileSync(join(root, 'src/styles', `${sheet}.css`), 'utf8')
  } catch {
    continue // a screen stylesheet that does not exist yet
  }
  for (const [name, line] of bareClasses(css)) {
    if (!owners.has(name)) owners.set(name, [])
    owners.get(name).push(`${sheet}.css:${line}`)
  }
}

const clashes = [...owners.entries()].filter(([, where]) => where.length > 1)
if (clashes.length > 0) {
  console.error('\nCSS scope check failed — a bare class is defined by more than one screen:\n')
  for (const [name, where] of clashes.sort()) {
    console.error(`  ${name}\n      ${where.join('\n      ')}`)
  }
  console.error(
    '\nScope each one to its screen root (e.g. `.invocations .split`), or move it\n' +
      'to primitives.css if every screen really should share it.\n',
  )
  process.exit(1)
}
console.log(`css scope ok — ${owners.size} bare classes, no cross-screen clash`)
