// Lays the built widget out as the Cauce instance serves it (ADR 0014, W4): dist/release/{version}/,
// one immutable file per version, and the tag a shop pastes, with its Subresource Integrity hash.
import { createHash } from 'node:crypto'
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'

const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url)))
const bytes = readFileSync(new URL('../dist/cauce-widget.js', import.meta.url))
const integrity = 'sha384-' + createHash('sha384').update(bytes).digest('base64')
const dir = new URL(`../dist/release/${version}/`, import.meta.url)
mkdirSync(dir, { recursive: true })
copyFileSync(new URL('../dist/cauce-widget.js', import.meta.url), new URL('cauce-widget.js', dir))

const tag = `<script src="https://INSTANCE/web/widget/${version}/cauce-widget.js"
        integrity="${integrity}" crossorigin="anonymous"
        data-cauce-key="pk_..." data-cauce-instance="https://INSTANCE" defer></script>
`
writeFileSync(new URL('embed.html', dir), tag)
writeFileSync(new URL('integrity.txt', dir), integrity + '\n')
console.log(`release ${version}: /web/widget/${version}/cauce-widget.js  integrity ${integrity}`)
