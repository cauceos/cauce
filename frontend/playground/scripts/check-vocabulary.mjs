/**
 * Guards the language of the audit chain screen.
 *
 * The backend has an integration test asserting that a chain-verification
 * response never contains a set of words that turn a computational fact
 * into a conclusion about rules or obligations. The screen that renders
 * that response inherits the rule: it describes a verifiable technical
 * capability, and nothing beyond it.
 *
 * Scope is that screen and the wire types it renders. The terms are ordinary English
 * elsewhere —
 * "duplicate names are legal" on the Agents screen means "the API permits
 * them", which is true and is not a claim of the kind this guards against.
 * A repo-wide scan would drown the rule in false positives and be turned
 * off within a week.
 *
 * The word list lives here and nowhere else, and this file excludes
 * itself: a source comment enumerating the terms would trip its own rule.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, relative } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
// src/api/types.ts carries the wire contract's doc comments for the same screen: the
// words a developer reads next to the type are as much surface as the rendered copy.
const TARGETS = ['src/views/audit', 'src/styles/audit.css', 'src/api/types.ts']

const FORBIDDEN = [
  'compliant',
  'gdpr',
  'legal',
  'court',
  'admissible',
  'certified',
  'guaranteed',
  'tamper-proof',
  'protected',
]

function filesUnder(path) {
  const full = join(root, path)
  let stats
  try {
    stats = statSync(full)
  } catch {
    return [] // not created yet
  }
  if (stats.isFile()) return [full]
  return readdirSync(full, { withFileTypes: true }).flatMap((entry) =>
    filesUnder(join(path, entry.name)),
  )
}

const hits = []
for (const file of TARGETS.flatMap(filesUnder)) {
  const lines = readFileSync(file, 'utf8').split('\n')
  lines.forEach((line, index) => {
    const lower = line.toLowerCase()
    for (const term of FORBIDDEN) {
      if (lower.includes(term)) {
        hits.push({ file: relative(root, file), line: index + 1, term, text: line.trim() })
      }
    }
  })
}

if (hits.length > 0) {
  console.error('\nVocabulary check failed — the audit chain screen states a conclusion it cannot support:\n')
  for (const hit of hits) {
    console.error(`  ${hit.file}:${hit.line}  "${hit.term}"`)
    console.error(`      ${hit.text}`)
  }
  console.error(
    '\nDescribe what the verification technically does, the way the API does.\n' +
      'The backend asserts the same rule on its response (ChainVerificationApiIT).\n',
  )
  process.exit(1)
}
console.log('vocabulary ok — audit chain screen claims nothing it cannot verify')
