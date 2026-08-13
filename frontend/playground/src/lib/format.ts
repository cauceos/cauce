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
export function formatUtc(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ` +
    `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`
  )
}
