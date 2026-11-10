// Prints the widget's weight as a shop downloads it, and fails above the budget (ADR 0014, W4).
import { readFileSync } from 'node:fs'
import { gzipSync, brotliCompressSync } from 'node:zlib'

const BUDGET_GZIP_BYTES = 20 * 1024
const file = new URL('../dist/cauce-widget.js', import.meta.url)
const bytes = readFileSync(file)
const gzip = gzipSync(bytes, { level: 9 }).length
const brotli = brotliCompressSync(bytes).length
const kb = (n) => (n / 1024).toFixed(1) + ' KB'

console.log(`cauce-widget.js: ${kb(bytes.length)} raw, ${kb(gzip)} gzip, ${kb(brotli)} brotli (budget ${kb(BUDGET_GZIP_BYTES)} gzip)`)
if (gzip > BUDGET_GZIP_BYTES) {
  console.error('The widget is over its weight budget.')
  process.exit(1)
}
