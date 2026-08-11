/**
 * Estrato mark (V4, dark variant — brand.md spec). Strokes are bound to the
 * theme tokens so the mark always matches the palette.
 */
export function EstratoLogo({
  width = 42,
  height = 32,
  title = 'Cauce',
}: {
  width?: number
  height?: number
  title?: string
}) {
  return (
    <svg width={width} height={height} viewBox="0 0 80 60" fill="none" role="img" aria-label={title}>
      <path d="M 4 22 Q 40 72 76 22" stroke="var(--color-stone-600)" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M 12 22 Q 40 62 68 22" stroke="var(--color-slate-500)" strokeWidth="2.4" strokeLinecap="round" />
      <path d="M 20 22 Q 40 52 60 22" stroke="var(--color-moss-400)" strokeWidth="3.0" strokeLinecap="round" />
      <path d="M 28 22 Q 40 42 52 22" stroke="var(--color-teal-500)" strokeWidth="4.0" strokeLinecap="round" />
      <circle cx="40" cy="40" r="3" fill="var(--color-teal-500)" />
    </svg>
  )
}
