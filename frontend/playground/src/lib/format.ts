/** Small formatting helpers shared across the workspace areas. */

/** First 8 chars of an id plus an ellipsis — the mockups' truncation. */
export function shortId(id: string): string {
  return `${id.slice(0, 8)}…`
}

/**
 * Render an ISO instant as `YYYY-MM-DD HH:mm UTC` (the mockups' format).
 * Returns the raw input unchanged if it does not parse, so a surprising
 * server value is shown honestly rather than as "Invalid Date".
 */
/** `YYYY-MM-DD` in UTC, or null when the instant does not parse. */
export function utcDay(iso: string): string | null {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return null
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
}

/** `HH:mm UTC` — the thread timestamp. Falls back to the raw input. */
export function utcTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`
}

/** Seconds with one decimal (`2.8s`); minutes once it runs long. */
export function formatDuration(ms: number): string {
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

export function formatUtc(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ` +
    `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`
  )
}
