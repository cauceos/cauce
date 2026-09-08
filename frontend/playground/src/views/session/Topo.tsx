/**
 * Estrato topography — the session screen's full-bleed signature. Purely
 * presentational.
 *
 * One SVG for all three viewports: the desktop geometry is drawn once and
 * `xMidYMin slice` crops it around the nadir as the container narrows (the
 * heights per breakpoint live in session.css). Every stroke is a gradient
 * that is born and dies in transparent, so no arc ends on a hard edge.
 *
 * Colours come from the tokens, never a literal: `stop-color` is a CSS
 * property, so var() resolves in it exactly as it would in a stylesheet.
 */
export function Topo() {
  return (
    <div className="topo" aria-hidden="true">
      <svg viewBox="0 0 1400 520" preserveAspectRatio="xMidYMin slice" fill="none">
        <defs>
          <linearGradient id="topo-stone" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-stone-600)" stopOpacity="0" />
            <stop offset="0.5" stopColor="var(--color-stone-600)" stopOpacity="0.8" />
            <stop offset="1" stopColor="var(--color-stone-600)" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="topo-slate" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-slate-500)" stopOpacity="0" />
            <stop offset="0.5" stopColor="var(--color-slate-500)" stopOpacity="0.5" />
            <stop offset="1" stopColor="var(--color-slate-500)" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="topo-moss" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-moss-400)" stopOpacity="0" />
            <stop offset="0.5" stopColor="var(--color-moss-400)" stopOpacity="0.46" />
            <stop offset="1" stopColor="var(--color-moss-400)" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="topo-teal" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-teal-500)" stopOpacity="0" />
            <stop offset="0.5" stopColor="var(--color-teal-500)" stopOpacity="0.58" />
            <stop offset="1" stopColor="var(--color-teal-500)" stopOpacity="0" />
          </linearGradient>
          <linearGradient id="topo-water" x1="0" x2="1">
            <stop offset="0" stopColor="var(--color-teal-500)" stopOpacity="0" />
            <stop offset="0.5" stopColor="var(--color-teal-500)" stopOpacity="0.15" />
            <stop offset="1" stopColor="var(--color-teal-500)" stopOpacity="0" />
          </linearGradient>
        </defs>

        <path className="arc draw" d="M -80 46 Q 700 780 1480 46" stroke="url(#topo-stone)" strokeWidth="1" />
        <path className="arc draw d2" d="M 80 46 Q 700 666 1320 46" stroke="url(#topo-slate)" strokeWidth="1" />
        <path className="arc draw d3" d="M 240 46 Q 700 552 1160 46" stroke="url(#topo-moss)" strokeWidth="1.1" />
        <path className="arc draw d4" d="M 400 46 Q 700 438 1000 46" stroke="url(#topo-teal)" strokeWidth="1.3" />

        <path className="nadir" d="M 300 330 H 1100" stroke="url(#topo-water)" strokeWidth="1" />
        <circle className="nadir" cx="700" cy="330" r="2.8" fill="var(--color-teal-500)" />
      </svg>
    </div>
  )
}
